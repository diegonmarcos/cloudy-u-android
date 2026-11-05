package com.diegonmarcos.superapp.perms

/**
 * #1519 THE Perms page as data (PermsPageTest): every catalogue row in a category, one dense line each (name, chip, reason,
 * actions), missing rows first, and a summary that never says "all granted" while a row is missing. The host draws these
 * (KitStoreRow); asking, settings and shell grants go through [PermsRequest], [PermsSettings] and [PermsShellGrant].
 */
enum class PermCategory(val title: String) {
    LOCATION_WIFI("Location & Wi-Fi"), NOTIFICATIONS("Notifications"), STORAGE("Storage & Media"), OTHER("Other runtime"),
    SHELL("Shell/ADB-only"), SPECIAL("Special access"), SAMSUNG("Samsung"), SYSTEM("System toggles"),
}

enum class RowChip(val word: String) {
    GRANTED("Granted"), MISSING("Missing"), DENIED_PERMANENTLY("Denied permanently"), NEEDS_SHELL("Needs shell"),
    INFO("Info"), NOT_APPLICABLE("Not applicable"),
}

enum class RowAction(val word: String) { GRANT("Grant"), SETTINGS("Settings"), SHELL("Shell") }

object PermsPage {
    /** Rows that inform and cannot be granted (the scan throttle) or that Android does not let an app read (OEM guides). */
    private val INFO_CHECKS = setOf("wifi_scan_throttle", "manual")

    fun isInfo(e: PermEntry): Boolean = e.check in INFO_CHECKS

    fun category(e: PermEntry): PermCategory = when {
        e.section == PermSection.SYSTEM -> PermCategory.SYSTEM
        e.section == PermSection.SAMSUNG || e.oem.equals("samsung", true) -> PermCategory.SAMSUNG
        e.kind == PermKind.ADB_ONLY -> PermCategory.SHELL
        e.group == "LOCATION" || e.group == "LOCATION_BACKGROUND" || e.id == "nearby_wifi" -> PermCategory.LOCATION_WIFI
        e.group == "NOTIFICATIONS" && e.kind == PermKind.RUNTIME -> PermCategory.NOTIFICATIONS
        e.group == "STORAGE" && e.id != "usb_host" -> PermCategory.STORAGE
        e.kind == PermKind.RUNTIME -> PermCategory.OTHER
        else -> PermCategory.SPECIAL
    }

    data class Row(
        val entry: PermEntry, val status: PermStatus, val category: PermCategory, val chip: RowChip,
        val reason: String, val actions: List<RowAction>, val info: Boolean,
    ) {
        val missing: Boolean get() = status.missing
        val counted: Boolean get() = status.state != PermState.NOT_APPLICABLE && !info
    }

    data class Group(val category: PermCategory, val rows: List<Row>) {
        val missing: Int get() = rows.count { it.counted && it.status.state != PermState.GRANTED }
        val count: String get() = "${rows.count { it.counted && it.status.state == PermState.GRANTED }} / ${rows.count { it.counted }}"
    }

    fun chip(e: PermEntry, s: PermStatus): RowChip = when {
        s.state == PermState.GRANTED -> RowChip.GRANTED
        s.state == PermState.NOT_APPLICABLE -> RowChip.NOT_APPLICABLE
        isInfo(e) -> RowChip.INFO
        s.state == PermState.PERMANENTLY_DENIED -> RowChip.DENIED_PERMANENTLY
        e.kind == PermKind.ADB_ONLY -> RowChip.NEEDS_SHELL
        else -> RowChip.MISSING
    }

    fun reason(e: PermEntry, s: PermStatus): String = when {
        s.state == PermState.PERMANENTLY_DENIED -> "Denied twice: allow it in Settings or grant it by shell"
        s.state == PermState.NOT_APPLICABLE -> s.reason.ifBlank { e.why }
        s.state == PermState.GRANTED -> e.why
        else -> listOf(e.why, s.reason).filter { it.isNotBlank() }.distinct().joinToString(" · ")
    }

    /** Grant = the dialog can answer; Settings = a page can; Shell = a shell line can (offered whenever the row is missing). */
    fun actions(e: PermEntry, s: PermStatus, shellable: Boolean): List<RowAction> {
        if (s.state == PermState.GRANTED || s.state == PermState.NOT_APPLICABLE) return emptyList()
        val out = ArrayList<RowAction>()
        if (PermsRequest.route(e, s.state) == PermsRequest.Route.DIALOG) out += RowAction.GRANT
        if (e.kind != PermKind.ADB_ONLY && (e.kind != PermKind.RUNTIME || s.state != PermState.DENIED || !e.autoRequest)) out += RowAction.SETTINGS
        if (shellable && s.missing) out += RowAction.SHELL
        return out
    }

    private fun rank(r: Row): Int = when {
        r.missing -> 0
        r.status.state == PermState.UNKNOWN || r.chip == RowChip.INFO -> 1
        r.status.state == PermState.GRANTED -> 2
        else -> 3
    }

    fun rows(entries: List<PermEntry>, status: (PermEntry) -> PermStatus, shellable: (PermEntry) -> Boolean): List<Row> =
        entries.map { e ->
            val s = status(e)
            Row(e, s, category(e), chip(e, s), reason(e, s), actions(e, s, shellable(e)), isInfo(e))
        }

    /** Categories in page order, empty ones left out, missing rows first inside each (catalogue order kept). */
    fun groups(rows: List<Row>): List<Group> =
        PermCategory.values().mapNotNull { c ->
            rows.filter { it.category == c && it.status.state != PermState.NOT_APPLICABLE }
                .sortedBy { rank(it) }.takeIf { it.isNotEmpty() }?.let { Group(c, it) }
        }

    /** Every applicable row counts (special and OEM ones too); only rows Android cannot report (info) stay out. */
    data class Summary(val granted: Int, val total: Int) {
        val missing: Int get() = total - granted
        val allGranted: Boolean get() = total > 0 && granted == total
        val text: String get() = "$granted of $total granted, $missing missing"
        val fraction: Float get() = if (total == 0) 1f else granted.toFloat() / total
    }

    fun summary(rows: List<Row>): Summary = rows.filter { it.counted }.let { c -> Summary(c.count { it.status.state == PermState.GRANTED }, c.size) }

    /** One step of "Ask all missing". */
    sealed interface Step {
        data class Dialog(val entries: List<PermEntry>) : Step
        data class Shell(val row: Row) : Step
        data class Settings(val row: Row) : Step
    }

    /**
     * The next step over the rows still missing. [asked] = ids the dialog already answered (it is never shown twice in a walk),
     * [walked] = ids already handed to the shell or their page; both only grow, so the walk ends. One combined dialog for every
     * runtime row first, then per still-missing row the shell when a channel is up and a line exists (an adb-only row always
     * goes to the shell), else its settings page.
     */
    fun nextStep(rows: List<Row>, walked: Set<String>, channelUp: Boolean, shellable: (PermEntry) -> Boolean, asked: Set<String> = emptySet()): Step? {
        val missing = rows.filter { it.missing && !it.info }
        val dialog = missing.filter { RowAction.GRANT in it.actions && it.entry.id !in asked }
        if (dialog.isNotEmpty()) return Step.Dialog(dialog.map { it.entry })
        val r = missing.firstOrNull { it.entry.id !in walked } ?: return null
        return when {
            channelUp && shellable(r.entry) -> Step.Shell(r)
            r.entry.kind == PermKind.ADB_ONLY -> Step.Shell(r)
            else -> Step.Settings(r)
        }
    }
}

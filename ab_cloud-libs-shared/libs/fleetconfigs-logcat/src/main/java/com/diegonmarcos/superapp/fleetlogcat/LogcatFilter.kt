package com.diegonmarcos.superapp.fleetlogcat

/*
 * #1828 the Logcat page's filters, for every fleet app: by LEVEL (all, or at least debug / info / warning, or errors only), by
 * KIND of log (crashes, network, push & sync, UI, system, the app's own) and by a free-text search over tag and message. Pure
 * (no Android): FleetLogcatFilterTest covers it on the JVM, and the page and its Copy / Save / Share exports apply the same filter.
 */

/** The lowest level shown. [ALL] also keeps separator / malformed lines. */
public enum class LogLevelFilter(public val label: String, internal val minRank: Int) {
    ALL("All", 0),
    DEBUG("Debug+", 2),
    INFO("Info+", 3),
    WARN("Warnings+", 4),
    ERROR("Errors", 5),
}

/** What a line is about, from its tag (and level for crashes). Exactly one kind per line: the first that matches, in this order. */
public enum class LogKind(public val label: String) {
    CRASH("Crashes"),
    NETWORK("Network"),
    SYNC("Push & sync"),
    UI("UI"),
    SYSTEM("System"),
    APP("App"),
}

/** One filter state. [kinds] empty = every kind. */
public data class LogcatFilter(
    public val level: LogLevelFilter = LogLevelFilter.ALL,
    public val kinds: Set<LogKind> = emptySet(),
    public val query: String = "",
) {
    /** True when nothing is filtered out. */
    public val isOpen: Boolean get() = level == LogLevelFilter.ALL && kinds.isEmpty() && query.isBlank()

    /** A one-line description for the exports ("" when open). */
    public fun describe(): String = if (isOpen) "" else listOfNotNull(
        level.takeIf { it != LogLevelFilter.ALL }?.label,
        kinds.takeIf { it.isNotEmpty() }?.let { k -> LogKind.entries.filter { it in k }.joinToString("+") { it.label } },
        query.trim().takeIf { it.isNotEmpty() }?.let { "\"$it\"" },
    ).joinToString(", ")

    public fun matches(r: FleetLogExport.Record): Boolean {
        if (r.level == ' ') return level == LogLevelFilter.ALL && kinds.isEmpty() && (query.isBlank() || r.raw.contains(query.trim(), ignoreCase = true))
        if (levelRank(r.level) < level.minRank) return false
        if (kinds.isNotEmpty() && logKindOf(r) !in kinds) return false
        val q = query.trim()
        return q.isEmpty() || r.tag.contains(q, ignoreCase = true) || r.message.contains(q, ignoreCase = true)
    }

    /** The raw lines that pass, the last [max] of them. */
    public fun apply(lines: List<String>, max: Int = Int.MAX_VALUE): List<String> =
        lines.filter { it.isNotBlank() && matches(FleetLogExport.parse(it)) }.takeLast(max)
}

internal fun levelRank(c: Char): Int = when (c) {
    'V' -> 1; 'D' -> 2; 'I' -> 3; 'W' -> 4; 'E' -> 5; 'F', 'A' -> 6; else -> 0
}

private val CRASH_TAGS = setOf("AndroidRuntime", "DEBUG", "libc", "crash_dump64", "crash_dump32", "tombstoned", "CrashReports")
private val NETWORK = Regex("(?i)(okhttp|http|network|net\\b|netd|socket|dns|ssl|tls|conscrypt|jmap|imap|smtp|wireguard|wg\\b|fetch|connect|endpoint|mesh|cronet)")
private val SYNC = Regex("(?i)(push|sync|worker|workmanager|wm-|job|alarm|fcm|unifiedpush|backoff|schedul)")
private val UI = Regex("(?i)(compose|view|activity|window|choreographer|input|ime|surface|render|hwui|webview|chromium|toast|ui\\b|nav|frame|gesture)")
private val SYSTEM = Regex("(?i)(vibrator|audio|sensor|binder|\\bart\\b|zygote|system|package|permission|gralloc|gpu|egl|adreno|mali|bluetooth|location|camera|media|power|battery|thermal|selinux|avc|linker|ziparchive|nativeloader|openglrenderer|resources|asset|storage|sqlite|cursorwindow|strictmode|studio\\.)")

/** The kind of one record; see [LogKind]. */
public fun logKindOf(r: FleetLogExport.Record): LogKind = when {
    r.level == 'F' || r.level == 'A' || r.tag in CRASH_TAGS || r.message.startsWith("FATAL EXCEPTION") -> LogKind.CRASH
    NETWORK.containsMatchIn(r.tag) -> LogKind.NETWORK
    SYNC.containsMatchIn(r.tag) -> LogKind.SYNC
    UI.containsMatchIn(r.tag) -> LogKind.UI
    SYSTEM.containsMatchIn(r.tag) -> LogKind.SYSTEM
    else -> LogKind.APP
}

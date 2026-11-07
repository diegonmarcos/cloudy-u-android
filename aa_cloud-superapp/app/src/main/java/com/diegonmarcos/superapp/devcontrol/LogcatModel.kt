package com.diegonmarcos.superapp.devcontrol

/**
 * The pure half of the two Logcat pages (no Android, no Compose): parsing the `logcat -v threadtime -v uid` lines the shared
 * LogPipe stream serves, the Filters (minimum level, tag, search), Clear as a mark in the stream, and the combined text a
 * multi-app export writes. Configs ▸ Launcher ▸ Logcat (this app only) and Configs ▸ Watchtower - Obsv ▸ Logcat Fleet both
 * draw from it, so the two pages cannot filter differently.
 *
 * Line layout: `MM-DD HH:MM:SS.mmm UID PID TID L TAG: msg`. Anything else (logcat's own `--------- beginning of main`
 * separators, LogPipe's "(no matching lines …)" health answers) is not a record: level ' ', uid -1.
 */
internal object LogcatModel {

    /** The levels the Filters offer, lowest first (V shows everything). */
    val LEVELS = listOf('V', 'D', 'I', 'W', 'E')

    class Rec(val raw: String, val uid: Int, val level: Char, val tag: String)

    private val WS = Regex("\\s+")

    fun parseLine(line: String): Rec {
        val f = line.trim().split(WS)
        val level = if (f.size > 5 && f[5].length == 1 && rank(f[5][0]) >= 0) f[5][0] else ' '
        if (level == ' ') return Rec(line, -1, ' ', "")
        return Rec(line, f[2].toIntOrNull() ?: -1, level, f.getOrNull(6).orEmpty().trimEnd(':'))
    }

    fun parse(text: String): List<Rec> = text.lineSequence().filter { it.isNotBlank() }.map(::parseLine).toList()

    /** V < D < I < W < E < F; -1 for anything that is not a level letter. */
    fun rank(c: Char): Int = when (c) { 'V' -> 0; 'D' -> 1; 'I' -> 2; 'W' -> 3; 'E' -> 4; 'F', 'A' -> 5; else -> -1 }

    /**
     * The Filters. [minLevel] keeps that level and above; [tag] and [query] are case-insensitive substrings (tag against the
     * tag column, query against the whole line). With no filter set every line passes, record or not; with one set, only records.
     */
    fun filter(recs: List<Rec>, minLevel: Char, tag: String, query: String): List<Rec> {
        val min = rank(minLevel)
        val t = tag.trim()
        val q = query.trim()
        if (min <= 0 && t.isEmpty() && q.isEmpty()) return recs
        return recs.filter { r ->
            r.level != ' ' && rank(r.level) >= min &&
                (t.isEmpty() || r.tag.contains(t, ignoreCase = true)) &&
                (q.isEmpty() || r.raw.contains(q, ignoreCase = true))
        }
    }

    /** Clear = remember the last line on screen; only what follows it is shown. A mark the ring has already dropped shows all. */
    fun afterMark(recs: List<Rec>, mark: String?): List<Rec> {
        if (mark == null) return recs
        val at = recs.indexOfLast { it.raw == mark }
        return if (at < 0) recs else recs.drop(at + 1)
    }

    fun text(recs: List<Rec>): String = recs.joinToString("\n") { it.raw }

    /** Only the lines of [uids]. */
    fun byUid(recs: List<Rec>, uids: Set<Int>): List<Rec> = recs.filter { it.uid in uids }

    /** The export of several apps: one titled block per app, in the given order. */
    fun combine(blocks: List<Pair<String, List<Rec>>>): String =
        blocks.joinToString("\n\n") { (title, recs) -> "===== $title (${recs.size} lines) =====\n" + text(recs) }

    /** The share sheet carries the text in an Intent (binder limit about 1 MB): the tail, with the cut said. */
    fun shareable(text: String, cap: Int = 200_000): String =
        if (text.length <= cap) text else "[tail: last $cap of ${text.length} chars]\n" + text.takeLast(cap)
}

/** Which apps the fleet logcat can name: every installed app, the fleet's own (com.diegonmarcos.*) first. */
internal object FleetLogcat {
    const val FLEET_PREFIX = "com.diegonmarcos."

    class App(val label: String, val pkg: String, val uid: Int) {
        val fleet: Boolean get() = pkg.startsWith(FLEET_PREFIX)
    }

    /** Fleet apps first, then A-Z. */
    fun order(apps: List<App>): List<App> = apps.sortedWith(compareBy({ !it.fleet }, { it.label.lowercase() }))

    fun apps(ctx: android.content.Context): List<App> {
        val pm = ctx.packageManager
        return runCatching {
            order(pm.getInstalledApplications(0).map { App(pm.getApplicationLabel(it).toString(), it.packageName, it.uid) })
        }.getOrDefault(emptyList())
    }

    /**
     * uid → that app's lines, through the plane [AboutLogcat.read] already uses: with READ_LOGS held ONE in-process read of the
     * whole stream, split by uid; without it one shell-channel `logcat --uid` per app, which answers with the mechanism (and
     * the one-time grant) when no Shizuku / adb channel is up. This app's own uid is always served in-process.
     */
    fun read(ctx: android.content.Context, uids: Set<Int>, errorsOnly: Boolean): Map<Int, String> {
        if (uids.isEmpty()) return emptyMap()
        val me = android.os.Process.myUid()
        if (AboutLogcat.holdsReadLogs(ctx)) {
            val all = LogcatModel.parse(AboutLogcat.read(ctx, AboutLogcat.SCOPE_ALL_APPS, errorsOnly))
            return uids.associateWith { u -> LogcatModel.text(all.filter { it.uid == u }).ifBlank { "(no lines for this app in the buffer)" } }
        }
        return uids.associateWith { u -> AboutLogcat.read(ctx, if (u == me) AboutLogcat.SCOPE_THIS_APP else u, errorsOnly) }
    }
}

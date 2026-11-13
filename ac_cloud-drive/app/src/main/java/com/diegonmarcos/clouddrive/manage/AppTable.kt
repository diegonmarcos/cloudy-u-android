package com.diegonmarcos.clouddrive.manage

import com.diegonmarcos.clouddrive.files.FileOps

/**
 * Manage ▸ Apps: one row per installed app and the table's pure rules — ranking, sorting and how
 * each cell prints. A number the app could not read is NULL, never zero, and prints [DASH]: a zero
 * would read as "this app is empty" or "this app is not running", which is a claim we cannot make.
 *
 * Sources (AppSnapshot): the three sizes from StorageStatsManager.queryStatsForPackage, which needs
 * usage access (PACKAGE_USAGE_STATS) for any package but this one; [running] and [ramKb] from
 * ActivityManager (only this app's own processes on Android 5.1+) or, when the fleet's privileged
 * shell is up, from `ps -A -o NAME` and `dumpsys meminfo` (PSS).
 */
data class AppRow(
    val pkg: String,
    val label: String,
    val system: Boolean,
    val appBytes: Long?,
    val dataBytes: Long?,
    val cacheBytes: Long?,
    val running: Boolean?,
    val ramKb: Long?,
    /** Local-Apps parity with the removed Fleet Apps page: the installed version, and (when the package is a declared fleet app) its glyph and launch fallback. */
    val versionName: String? = null,
    val fleetIcon: String? = null,
    val fleetUrl: String? = null,
) {
    /** App + data + cache; null when any of them is unknown (a partial sum would rank wrongly). */
    val totalBytes: Long? get() = if (appBytes == null || dataBytes == null || cacheBytes == null) null else appBytes + dataBytes + cacheBytes
}

enum class AppColumn { NAME, VERSION, TOTAL, APP, DATA, CACHE, RUNNING, RAM }

object AppTable {
    const val DASH = "—"
    val DEFAULT_SORT = AppColumn.TOTAL

    /** Rows ordered by [column]; unknown values (null) always sink to the bottom, whichever the direction. Ties break by label. */
    fun sort(rows: List<AppRow>, column: AppColumn = DEFAULT_SORT, descending: Boolean = true): List<AppRow> {
        fun key(r: AppRow): Comparable<*>? = when (column) {
            AppColumn.NAME -> r.label.lowercase()
            AppColumn.VERSION -> r.versionName?.lowercase()
            AppColumn.TOTAL -> r.totalBytes
            AppColumn.APP -> r.appBytes
            AppColumn.DATA -> r.dataBytes
            AppColumn.CACHE -> r.cacheBytes
            AppColumn.RUNNING -> r.running
            AppColumn.RAM -> r.ramKb
        }
        val known = rows.filter { key(it) != null }
        val unknown = rows.filter { key(it) == null }.sortedBy { it.label.lowercase() }
        @Suppress("UNCHECKED_CAST")
        val cmp = compareBy<AppRow> { key(it) as Comparable<Any> }.let { if (descending) it.reversed() else it }.thenBy { it.label.lowercase() }
        return known.sortedWith(cmp) + unknown
    }

    fun size(bytes: Long?): String = if (bytes == null) DASH else FileOps.humanBytes(bytes)
    fun running(on: Boolean?, yes: String, no: String): String = when (on) { null -> DASH; true -> yes; false -> no }
    fun ram(kb: Long?): String = if (kb == null) DASH else FileOps.humanBytes(kb * 1024)

    /**
     * `dumpsys meminfo` → PSS in KB per PACKAGE: the "Total PSS by process" section, one line per
     * process (`  123,456K: com.foo:remote (pid 42 / activities)`); a package's processes are summed.
     */
    fun parseMeminfo(text: String): Map<String, Long> {
        val out = HashMap<String, Long>()
        var inSection = false
        val line = Regex("""^\s*([\d,]+)K:\s+(\S+)\s+\(pid\s+\d+""")
        for (l in text.lineSequence()) {
            if (l.trim().startsWith("Total PSS by process")) { inSection = true; continue }
            if (!inSection) continue
            if (l.isBlank()) { if (out.isNotEmpty()) break else continue }
            val m = line.find(l) ?: if (l.trimStart().startsWith("Total")) break else continue
            val kb = m.groupValues[1].replace(",", "").toLongOrNull() ?: continue
            val pkg = m.groupValues[2].substringBefore(':')
            out[pkg] = (out[pkg] ?: 0L) + kb
        }
        return out
    }

    /** `ps -A -o NAME` → the set of packages with a live process (`com.foo:service` counts for com.foo). */
    fun parsePs(text: String): Set<String> =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && it != "NAME" }.map { it.substringBefore(':') }.toSet()

    /** Fill running / RAM from the shell's answers; a package the shell could not see stays as it was. */
    fun withShell(rows: List<AppRow>, ps: Set<String>?, pss: Map<String, Long>?): List<AppRow> = rows.map { r ->
        r.copy(
            running = if (ps != null) r.pkg in ps else r.running,
            ramKb = pss?.get(r.pkg) ?: (if (ps != null && r.pkg !in ps) null else r.ramKb),
        )
    }
}

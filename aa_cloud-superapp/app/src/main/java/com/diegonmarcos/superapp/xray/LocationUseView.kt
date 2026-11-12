package com.diegonmarcos.superapp.xray

import com.diegonmarcos.superapp.perms.LocationUse
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * #1666 X-Ray App's "Location use": the app's own ledger ([LocationUse], last access per call site: time, API, trigger) and the
 * system's view of the same thing (the location app-ops, read through the SuperApp's shell broker when an ADB shell is up), so a
 * lit location dot can be traced to the call site that lit it. Pure: the page and the export render from these.
 */
object LocationUseView {

    const val PKG = "com.diegonmarcos.superapp"

    /** The location-class app-ops Android shows in the quick panel's "Recent app use" / the privacy indicator. */
    val OPS = listOf("FINE_LOCATION", "COARSE_LOCATION", "MONITOR_LOCATION", "MONITOR_HIGH_POWER_LOCATION")

    /** One segment per op: each matches the broker's SuperApp `appops get <pkg> <OP>` read rule. */
    val APPOPS_CMD: String = OPS.joinToString("; ") { "appops get $PKG $it" }

    fun time(atMs: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(atMs))

    /** One key/value row per call site, newest first. */
    fun rows(entries: List<LocationUse.Entry>): List<Pair<String, String>> =
        entries.map { it.site to "${time(it.atMs)} · ${it.api} · ${it.trigger}" }

    /** The ledger as plain text (the export's section), or a reason. */
    fun ledgerText(entries: List<LocationUse.Entry>): String =
        if (entries.isEmpty()) "no location-class access recorded since this build was installed"
        else rows(entries).joinToString("\n") { (k, v) -> "$k | $v" }

    /** The lines of an `appops get` answer that name a location op (mode, last access, duration / running). */
    fun opsLines(appops: String?): List<String> =
        appops.orEmpty().lineSequence().map { it.trim() }.filter { l -> OPS.any { l.startsWith("$it:") } }.toList()

    /** True when an op line says the access is ongoing right now (what lights the indicator). */
    fun running(line: String): Boolean = line.contains("running", ignoreCase = true) || Regex("""duration=-""").containsMatchIn(line)

    /** A one-line verdict for the digest: how many ops were accessed, which are running. */
    fun summary(appops: String?): String {
        val ls = opsLines(appops)
        if (ls.isEmpty()) return "no location op recorded"
        val run = ls.filter(::running).map { it.substringBefore(':') }
        val used = ls.filter { it.contains("time=") }.map { it.substringBefore(':') }
        return (if (run.isEmpty()) "none running" else "RUNNING ${run.joinToString(" ")}") +
            (if (used.isEmpty()) "" else " · accessed ${used.joinToString(" ")}")
    }
}

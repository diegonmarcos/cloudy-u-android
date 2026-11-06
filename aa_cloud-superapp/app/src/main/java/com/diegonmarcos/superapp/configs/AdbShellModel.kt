package com.diegonmarcos.superapp.configs

import com.diegonmarcos.superapp.adbdebug.WdAuditLog
import com.diegonmarcos.superapp.adbdebug.WdKeeperStatus
import com.diegonmarcos.superapp.shellbroker.AuditLog
import com.diegonmarcos.superapp.shellclient.ShellStatus

/**
 * #1522 The SuperApp's ADB Shell page as DATA: its sections in order, which of them start collapsed, the one primary action and
 * the pure row models. #1550 CONSOLE hosts the lib's full channel console (AdbShellPage, sections 1-7) that #1522 had dropped. [AdbShellBrokerPage] draws exactly this; no section is named anywhere else.
 */
internal object AdbShellModel {
    /** [collapsed] = folded until tapped (the two logs). [help] = the section's one short line, or empty. */
    enum class Section(val id: String, val title: String, val collapsed: Boolean, val help: String) {
        STATUS("status", "Status", false, ""),
        ACTIONS("actions", "Actions", false, "Test now runs a real shell command through the broker."),
        KEEPER("keeper", "Keep wireless debugging on", false, "Turns Wireless debugging back on after it drops."),
        CALLERS("callers", "Callers", false, "Fleet apps that use this broker, with what each may run."),
        AUDIT("audit", "Broker audit log", true, "Newest first. Commands only, never output."),
        KEEPER_LOG("keeper_log", "Keeper change log", true, "One row per Wireless debugging change, newest first."),
        CONSOLE("console", "Channel console", false, "Layers, connect plan, channel actions, log, declared needs, setup."),
    }

    val ORDER: List<Section> = Section.values().toList()

    /** The sections that start expanded. */
    fun initiallyOpen(): Set<String> = ORDER.filter { !it.collapsed }.map { it.id }.toSet()

    /** The ONE primary action of the status card: Pair until the phone is paired, then Re-arm. */
    enum class Primary(val label: String) { REARM("Re-arm"), PAIR("Pair") }

    fun primary(paired: Boolean): Primary = if (paired) Primary.REARM else Primary.PAIR

    /** The state glyph key of the live round trip: true = green, false = red, null = not checked yet. */
    fun verdict(st: ShellStatus?): Boolean? = st?.armed

    /** The exact line the card shows (reason and check time included). */
    fun detail(st: ShellStatus?): String = st?.line() ?: "Checking..."

    /** "37123" from "192.168.1.5:37123"; null when no port is known. */
    fun port(endpoint: String?): String? = endpoint?.substringAfterLast(':', "")?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }

    data class CallerRow(val key: String, val summary: String, val lastCallAt: Long?)

    /** Every allowlisted caller, its allowlist summary and the time of its newest audited call. */
    fun callers(summaries: List<Pair<String, String>>, audit: List<AuditLog.Entry>): List<CallerRow> =
        summaries.map { (k, s) -> CallerRow(k, s, audit.filter { it.caller == k }.maxOfOrNull { it.ts }) }

    /** The compact audit table: newest first, capped. */
    fun auditRows(audit: List<AuditLog.Entry>, max: Int = 30): List<AuditLog.Entry> = audit.takeLast(max).reversed()

    fun keeperRows(log: List<WdAuditLog.Entry>, max: Int = 30): List<WdAuditLog.Entry> = log.takeLast(max).reversed()

    /** "fleet.app" from "com.diegonmarcos.cloudstore"-style packages: the last two segments keep a row short. */
    fun shortCaller(pkg: String): String = pkg.split('.').takeLast(2).joinToString(".")

    fun rearmLine(s: WdKeeperStatus.Snapshot?, ago: (Long) -> String): String =
        s?.lastRearmAt?.let { ago(it) + (s.lastRearmBy?.let { b -> " by $b" } ?: "") } ?: "never"
}

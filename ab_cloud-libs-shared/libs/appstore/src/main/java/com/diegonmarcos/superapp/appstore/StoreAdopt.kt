package com.diegonmarcos.superapp.appstore

import android.content.Context
import com.diegonmarcos.superapp.updater.install.SilentReadiness
import java.util.concurrent.ConcurrentHashMap

/**
 * #1744 ADOPT: make Cloud Store the installer of record of every fleet package that was installed some other way (adb, a file
 * manager), so the next update stops prompting. Done by the fleet's ADB owner (Cloud C3 Webserver, reached through ShellClient)
 * with `pm set-installer <pkg> com.diegonmarcos.cloudstore` (hidden shell command of PackageManagerShellCommand, present on
 * API 29+ and so on 34-37; an older or stripped build answers "Unknown command", reported as a reason, never a crash).
 * The result is never assumed: the installer is READ BACK (`pm list packages -i`, falling back to PackageManager) and that real
 * state drives the row marker. Everything parsing text is pure so the unit suite drives every branch.
 */
internal object StoreAdopt {

    val STORE: String = SilentReadiness.INSTALLER_OF_RECORD

    enum class State { ADOPTED, ALREADY, FAILED, ADB_DOWN, UNSUPPORTED }

    /** One package's outcome; [reason] is shown in the detail view. */
    data class Result(val pkg: String, val state: State, val reason: String, val installer: String?)

    private val PKG = Regex("^[a-z][a-z0-9_]*(\\.[a-zA-Z0-9_]+)+$")

    /** The allowlisted command (null for a package name that is not a plain package id, so nothing odd reaches a shell). */
    fun command(pkg: String): String? = if (PKG.matches(pkg)) "pm set-installer $pkg $STORE" else null

    /** The one read-back command (the Store's allowlist entry already carries `pm list`). */
    const val READ_BACK = "pm list packages -i"

    /** Pure: `package:<pkg>  installer=<name|null>` lines to pkg -> installer (null when none). Lines of any other shape are skipped. */
    fun parseInstallers(out: String): Map<String, String?> = buildMap {
        for (raw in out.lineSequence()) {
            val l = raw.trim()
            if (!l.startsWith("package:")) continue
            val body = l.removePrefix("package:")
            val pkg = body.substringBefore(' ').trim()
            val inst = Regex("installer=(\\S+)").find(body)?.groupValues?.get(1)
            if (pkg.isNotEmpty()) put(pkg, inst?.takeIf { it != "null" })
        }
    }

    /**
     * Pure: the REAL cause line of a failed `pm set-installer`. The shell prints a header ("Exception occurred while executing
     * 'set-installer':") and the cause on the next line (java.lang.SecurityException: ...), so the first line alone says nothing:
     * take the first line naming an exception class with its message, else the lines after the header joined, else the first line.
     */
    fun causeOf(out: String): String {
        val lines = out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("at ") }.toList()
        val cause = lines.firstOrNull { Regex("^(Caused by: )?[\\w.$]+(Exception|Error)\\b.*").matches(it) }?.removePrefix("Caused by: ")
        val tail = lines.filterNot { it.startsWith("Exception occurred while executing") }.joinToString(" ").trim()
        return (cause ?: tail.ifEmpty { lines.firstOrNull().orEmpty() }).take(200)
    }

    /** Pure: why Android refused, as a per-package reason (what it is and who can change it), from the cause text. */
    fun classifyRefusal(cause: String): String {
        val low = cause.lowercase()
        return when {
            "update owner" in low || "updateowner" in low || "update ownership" in low || "ownership" in low ->
                "another installer holds update ownership of this package (Android 14+); only that installer or the user can hand it over: $cause"
            "system app" in low || "is a system" in low || "privileged" in low || "cannot set" in low && "system" in low ->
                "system or privileged package: the installer of record of a system app cannot be changed from the shell: $cause"
            "securityexception" in low || "not allowed" in low || "permission denial" in low || "not the installer" in low || "caller" in low ->
                "Android refuses the shell (uid 2000) to change this package's installer (it belongs to another installer or signature): $cause"
            "not installed" in low || "unknown package" in low || "no such package" in low || "illegalargument" in low ->
                "Android rejected the request for this package or for Cloud Store as installer: $cause"
            else -> cause
        }
    }

    /** Pure: the outcome of one `pm set-installer` call from its output. Null state = success (still verified by the read-back). */
    fun parseSetResult(out: String?): Pair<State?, String> {
        if (out == null) return State.ADB_DOWN to "no armed ADB shell (Cloud C3 Webserver is not installed or its shell is down)"
        val t = out.trim()
        val low = t.lowercase()
        return when {
            low.startsWith("denied by the shell broker") -> State.FAILED to t
            "unknown command" in low || "no shell command implementation" in low || "unknown option" in low ->
                State.UNSUPPORTED to "this Android build has no pm set-installer (${t.lineSequence().firstOrNull().orEmpty().take(120)})"
            "unknown package" in low || "no such package" in low || ("not installed" in low && "exception" !in low) ->
                State.FAILED to "package not installed"
            "exception" in low || "not allowed" in low || "permission denial" in low || low.startsWith("error") || low.startsWith("fail") ->
                State.FAILED to classifyRefusal(causeOf(t))
            else -> null to (t.ifBlank { "ok" })
        }
    }

    /** Pure: one line per refused package for the Cloud page (never truncated to one toast). */
    fun failureLines(rs: Collection<Result>): List<String> =
        rs.filter { it.state == State.FAILED || it.state == State.UNSUPPORTED }.sortedBy { it.pkg }.map { "${it.pkg}: ${it.reason}" }

    /** The failure lines of the last Adopt all run, kept for the Cloud page. */
    @Volatile var lastReport: List<String> = emptyList()

    /** Pure: the final result from a set output and the installer read back afterwards. */
    fun resolve(pkg: String, before: String?, setOut: String?, after: String?): Result {
        if (before == STORE) return Result(pkg, State.ALREADY, "Cloud Store is already the installer of record", before)
        val (state, why) = parseSetResult(setOut)
        if (state != null) return Result(pkg, state, why, after ?: before)
        return if (after == STORE) Result(pkg, State.ADOPTED, "installer of record set to Cloud Store (read back)", after)
        else Result(pkg, State.FAILED, "pm set-installer ran but the installer reads back as ${after ?: "none"}", after)
    }

    /** Pure: the row marker. The full text lives in the detail view; the row shows at most this in its right-hand status line. */
    fun marker(nextUpdateLine: String?): String = when {
        nextUpdateLine == null -> ""
        nextUpdateLine == "silent" -> " · silent"
        else -> " · prompts"
    }

    /** Pure: the packages that still need adopting, from pkg -> installer. */
    fun needing(installers: Map<String, String?>, fleetPkgs: Collection<String>): List<String> =
        fleetPkgs.filter { it in installers && installers[it] != STORE }.distinct().sorted()

    // ── state, last real outcome per package ─────────────────────────────────────────────────────────────────

    private val last = ConcurrentHashMap<String, Result>()
    fun lastResult(pkg: String): Result? = last[pkg]
    fun clear() = last.clear()

    /** The detail-view line for [pkg], or null when there is nothing to say. */
    fun detailLine(pkg: String): String? = last[pkg]?.let {
        when (it.state) {
            State.ADOPTED, State.ALREADY -> "Installer: Cloud Store (${it.reason})"
            State.ADB_DOWN -> "Adopt not run: ${it.reason}"
            State.UNSUPPORTED -> "Adopt unavailable: ${it.reason}"
            State.FAILED -> "Adopt failed: ${it.reason}"
        }
    }

    /**
     * Adopt every package of [pkgs] installed from another source. Blocking, off the main thread. Never throws: with ADB down every
     * package gets an ADB_DOWN result with the reason. The installer is read back after the calls.
     */
    fun adopt(ctx: Context, pkgs: Collection<String>): List<Result> = runCatching {
        // Cloud Store itself is always part of the pass, so its own self-update is silent too.
        val installed = (pkgs + STORE).distinct().filter { runCatching { ctx.packageManager.getPackageInfo(it, 0); true }.getOrDefault(false) }
        val status = runCatching { StoreShell.status(ctx) }.getOrNull()
        if (status == null || !status.armed) {
            val why = status?.line() ?: "ADB shell state unknown"
            return@runCatching installed.map { Result(it, State.ADB_DOWN, why, SilentReadiness.installerOf(ctx, it)).also { r -> last[it] = r } }
        }
        val before = parseInstallers(StoreShell.exec(ctx, READ_BACK).orEmpty()).ifEmpty { null }
        fun inst(p: String) = before?.get(p) ?: SilentReadiness.installerOf(ctx, p)
        val todo = installed.filter { inst(it) != STORE }
        val outs = todo.associateWith { p -> command(p)?.let { StoreShell.exec(ctx, it) } ?: "Failure: not a package id" }
        val after = if (todo.isEmpty()) null else parseInstallers(StoreShell.exec(ctx, READ_BACK).orEmpty()).ifEmpty { null }
        installed.map { p ->
            val a = after?.get(p) ?: SilentReadiness.installerOf(ctx, p)
            resolve(p, inst(p), outs[p], a).also { last[p] = it }
        }
    }.getOrElse { e -> pkgs.map { Result(it, State.FAILED, "adopt could not run (${e.javaClass.simpleName})", null) } }

    /** After an install: adopt just [pkg] when ADB is up. Silent no-op otherwise (the reason is kept for the detail view). */
    fun adoptAfterInstall(ctx: Context, pkg: String) = adoptAfterInstall(ctx, listOf(pkg))
    fun adoptAfterInstall(ctx: Context, pkgs: Collection<String>) { runCatching { adopt(ctx, pkgs) } }
}

package com.diegonmarcos.superapp.updater

import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The report the Diagnose screen shows after a failed install.
 *
 * Written because "INSTALL_PARSE_FAILED_NOT_APK: failed to load asset path"
 * names a symptom and hides every cause: a truncated stage, a full disk, a
 * signature that does not match the installed copy, a stale session holding a
 * slot. Each of those is one line of local state, and none of it reaches the
 * person looking at the phone. So collect them all, once, in an order that
 * reads top-down: what failed, then the file, then the device, then the app.
 *
 * Plain text on purpose — it is read by a human first, pasted into a chat
 * second, and parsed by a tool a distant third. Everything here is local and
 * non-secret: no tokens, no account identifiers, no message contents.
 */
object InstallDiagnostics {

    private fun ts() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun human(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
        b >= 1024      -> "%.1f kB".format(b / 1024.0)
        else           -> "$b B"
    }

    private fun freeBytes(path: File): Long = runCatching {
        val s = StatFs(path.absolutePath); s.availableBlocksLong * s.blockSizeLong
    }.getOrDefault(-1L)

    /**
     * [apk] is the file we tried to install, when there was one — its size and
     * the free space beside it are the pair that explains a truncated stage.
     */
    fun collect(ctx: Context, appId: String, pkg: String, error: String, apk: File? = null): String {
        val sb = StringBuilder(4096)
        fun line(k: String, v: Any?) = sb.append(k.padEnd(22)).append(": ").append(v).append('\n')

        sb.append("── install failure ────────────────────────────────\n")
        line("when", ts())
        line("app", appId)
        line("package", pkg)
        line("error", error)

        sb.append("\n── the file ───────────────────────────────────────\n")
        if (apk == null) {
            line("apk", "(none — failed before download)")
        } else {
            line("apk", apk.absolutePath)
            line("exists", apk.exists())
            line("size", if (apk.exists()) "${apk.length()} B (${human(apk.length())})" else "—")
            // A zip ends with the End Of Central Directory record; a truncated
            // download is a valid prefix of a valid APK and looks fine until
            // the installer reads the tail. This is the cheapest way to tell
            // "the download stopped early" from "the APK is wrong".
            line("zip tail ok", runCatching {
                apk.exists() && apk.length() > 22 && java.util.zip.ZipFile(apk).use { it.size() > 0 }
            }.getOrDefault(false))
        }

        sb.append("\n── space ──────────────────────────────────────────\n")
        line("free (data)", human(freeBytes(Environment.getDataDirectory())))
        line("free (external)", human(freeBytes(ctx.getExternalFilesDir(null) ?: ctx.filesDir)))
        line("free (cache)", human(freeBytes(ctx.cacheDir)))

        sb.append("\n── installer state ────────────────────────────────\n")
        line("can install unknown", runCatching {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()
        }.getOrDefault(false))
        // Android caps an installer at 50 concurrent sessions; leaked ones are
        // invisible and eventually make every install fail.
        line("our open sessions", openSessions(ctx))
        line("session budget", "${com.diegonmarcos.superapp.updater.install.InstallSessions.BUDGET_CAP} (Android's cap is 50)")

        sb.append("\n── installed copy ─────────────────────────────────\n")
        val info = runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.getOrNull()
        if (info == null) {
            line("installed", "no — this is a first install, not an update")
        } else {
            line("installed", "yes")
            line("versionName", info.versionName)
            line("versionCode", if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong())
            line("installer", runCatching {
                ctx.packageManager.getInstallerPackageName(pkg)
            }.getOrNull() ?: "unknown")
        }

        sb.append("\n── silent update (#1675) ──────────────────────────\n")
        line("next update", SilentReadinessLine.of(ctx, pkg))
        line("installer of record", com.diegonmarcos.superapp.updater.install.SilentReadiness.INSTALLER_OF_RECORD)
        sessions(ctx).filter { it.contains(" $pkg ") }.takeLast(8).forEach { sb.append("  ").append(it).append('\n') }

        sb.append("\n── device ─────────────────────────────────────────\n")
        line("model", "${Build.MANUFACTURER} ${Build.MODEL}")
        line("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        line("abis", Build.SUPPORTED_ABIS.joinToString(","))
        line("host app", "${ctx.packageName} ${BuildConfig.GIT_SHORT_SHA}")

        sb.append("\n── recent log (this app only) ─────────────────────\n")
        // ONE logcat reader in the constellation: devtools owns it, the About
        // page's live viewer and the debug API's /diagnostics/logcat use the
        // same one. No second copy here.
        sb.append(runCatching {
            com.diegonmarcos.superapp.devtools.AppDebugServer.readLogcat(120)
        }.getOrElse { "(unavailable: ${it.message})\n" })
        sb.append("\nFull live log: Configs > About > Logcat (All | Errors, copy-all).\n")
        return sb.toString()
    }

    /** #1735 How many PackageInstaller sessions this app holds open (-1 unreadable). */
    fun openSessions(ctx: Context): Int = com.diegonmarcos.superapp.updater.install.InstallSessions.openCount(ctx)

    /** #1735 Reap on demand (the Diagnose button): returns "N open before, M abandoned, K now". Never throws. */
    fun reapSessions(ctx: Context): String {
        val before = openSessions(ctx)
        val n = com.diegonmarcos.superapp.updater.install.InstallSessions.reapOwn(ctx, aggressive = true)
        val msg = "open sessions: $before before, $n abandoned, ${openSessions(ctx)} now"
        recordSession(ctx, ctx.packageName, "reaped", msg)
        return msg
    }

    // ── #1675 the session log: what each install session did (silent / prompted / failed + Android's message) ──────────

    private const val SESSION_LOG = "install-sessions.log"
    private const val SESSION_LOG_MAX = 200

    /** Pure: one log line. */
    fun sessionLine(whenText: String, pkg: String, outcome: String, detail: String): String =
        "$whenText $pkg $outcome" + if (detail.isBlank()) "" else " — ${detail.replace('\n', ' ').take(300)}"

    /** Pure: the session outcome named by PackageInstaller's terminal [status], given whether a confirmation was asked. */
    fun sessionOutcome(status: Int, prompted: Boolean): String = when (status) {
        PackageInstaller.STATUS_PENDING_USER_ACTION -> "prompting"
        PackageInstaller.STATUS_SUCCESS -> if (prompted) "prompted" else "silent"
        else -> "failed"
    }

    /** Append one line (kept to the last [SESSION_LOG_MAX]). Never throws. */
    fun recordSession(ctx: Context, pkg: String, outcome: String, detail: String) {
        runCatching {
            val f = File(ctx.filesDir, SESSION_LOG)
            val lines = (if (f.exists()) f.readLines() else emptyList()) + sessionLine(ts(), pkg, outcome, detail)
            f.writeText(lines.takeLast(SESSION_LOG_MAX).joinToString("\n", postfix = "\n"))
        }
    }

    /** The session log, oldest first; empty when none. */
    fun sessions(ctx: Context): List<String> =
        runCatching { File(ctx.filesDir, SESSION_LOG).takeIf { it.exists() }?.readLines().orEmpty() }.getOrDefault(emptyList())

    private object SilentReadinessLine {
        fun of(ctx: Context, pkg: String): String =
            com.diegonmarcos.superapp.updater.install.SilentReadiness.lastVerdict(ctx, pkg) ?: "not assessed yet"
    }
}

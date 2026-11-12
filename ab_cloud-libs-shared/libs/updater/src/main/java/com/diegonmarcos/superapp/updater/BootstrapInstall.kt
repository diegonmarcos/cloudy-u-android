package com.diegonmarcos.superapp.updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * THE FLOOR. Install an APK with ZERO privileged access.
 *
 * ## Why this exists
 * Every other install path in this module needs something the device might not
 * have: [com.diegonmarcos.superapp.updater.install.ShellInstall] needs a live
 * shell channel (Shizuku or a paired embedded adb client), and the
 * PackageInstaller session path is currently disabled outright (see
 * `Fleet.ALLOW_PROMPTING_FALLBACK`). So a phone that has lost its privileged
 * channel cannot install anything — INCLUDING the build that would give it the
 * channel back. That is a terminal state reachable by doing nothing, and the
 * only escape was a human with a USB cable, which does not scale past one
 * device, let alone thousands.
 *
 * This path needs none of it. It hands a `content://` URI to the ordinary
 * Android package installer with `ACTION_VIEW`, which is the same thing a file
 * manager does when you tap an APK. The user sees the standard system
 * confirmation and nothing else. It is slower and noisier than the silent
 * path, and that is precisely why it is the FALLBACK and not the default —
 * but it cannot be taken away by a lost pairing.
 *
 * ## What it does NOT do
 * It does not download, and it does not verify. It CANNOT: the only way in is
 * [Fleet.download], which returns a
 * [com.diegonmarcos.superapp.updater.apk.VerifiedApk], and that type's
 * constructor is private with every factory ending at `ApkIntegrity`. So the
 * bytes handed to the system installer here carry exactly the same guarantee
 * (release sha256 sidecar, or GHCR layer digest, or declared length) as the
 * bytes handed to the privileged installer. There is no second downloader and
 * no weakened check — writing one would have meant duplicating the code this
 * deliberately reuses.
 *
 * ## FileProvider authority
 * `<applicationId>.updater.fileprovider`, declared in THIS module's manifest
 * rather than each app's. Every constellation app shares the
 * `com.diegonmarcos.*` package prefix and several of them link `libs:updater`,
 * so a hand-written authority is a collision waiting to happen — two installed
 * apps declaring the same provider authority makes the second one fail to
 * install at all. `${applicationId}` is unique by construction, and declaring
 * it once here means a new consumer cannot forget it or get it wrong.
 */
object BootstrapInstall {

    private const val TAG = "Updater/Bootstrap"

    /** Must match the `android:authorities` in this module's manifest. */
    fun authority(ctx: Context): String = ctx.packageName + ".updater.fileprovider"

    /**
     * What the direct-install path is about to hand the system, described well
     * enough for the UI to show the user that it really is newer.
     *
     * [installedCode] is null when the app is not installed at all, which is a
     * legitimate bootstrap case (a lib the fleet needs, or a reinstall after a
     * failed update wiped the package).
     */
    data class Candidate(
        val appId: String,
        val label: String,
        val pkg: String,
        val file: File,
        val evidence: String,
        val versionName: String?,
        val versionCode: Long,
        val installedVersionName: String?,
        val installedCode: Long?,
    ) {
        /** True when this really is an upgrade. The UI must be able to say so
         *  rather than asking the user to trust a button. */
        val isNewer: Boolean get() = installedCode == null || versionCode > installedCode

        /** "3 days behind", or null when nothing decodable to say. */
        val behindBy: String? get() =
            installedCode?.let { BuildAge.behindBy(it, versionCode) }

        fun versionLine(): String {
            val have = when {
                installedCode == null -> "not installed"
                else -> "${installedVersionName ?: "?"} (${BuildAge.describe(installedCode)})"
            }
            val get = "${versionName ?: "?"} (${BuildAge.describe(versionCode)})"
            return "installed: $have\navailable: $get"
        }
    }

    /**
     * Fetch + verify [app] through the ordinary source ladder and describe the
     * result. Network, so off the main thread. Throws with the source's own
     * message when nothing could serve verified bytes — a silent null here
     * would put the caller right back in the "it did nothing and said nothing"
     * failure this whole feature exists to end.
     */
    fun stage(ctx: Context, app: Fleet.App): Candidate = stageVerified(ctx, app).first

    /** [stage] plus the [com.diegonmarcos.superapp.updater.apk.VerifiedApk] it read, for the session path (#1675). */
    private fun stageVerified(ctx: Context, app: Fleet.App): Pair<Candidate, com.diegonmarcos.superapp.updater.apk.VerifiedApk> {
        val apk = Fleet.download(ctx, app)        // VerifiedApk — verification is its type
        val identity = com.diegonmarcos.superapp.updater.apk.ApkIntegrity.identify(ctx, apk.file)
        val installed = installedInfo(ctx, app)
        return Candidate(
            appId = app.id,
            label = app.label,
            pkg = identity?.pkg ?: app.pkg,
            file = apk.file,
            evidence = apk.evidence,
            versionName = archiveVersionName(ctx, apk.file),
            versionCode = identity?.versionCode ?: -1L,
            installedVersionName = installed?.first,
            installedCode = installed?.second,
        ) to apk
    }

    /**
     * The whole affordance: fetch, verify, hand to the system installer.
     *
     * Returns the [Candidate] that was offered, or the failure. Never throws
     * at the caller — the recovery screen is the LAST surface a stranded user
     * reaches, and a crash there is the end of the line.
     */
    fun launch(ctx: Context, app: Fleet.App): Result<Candidate> = runCatching {
        val (candidate, verified) = stageVerified(ctx, app)
        Log.i(TAG, "direct install ${candidate.appId}: ${candidate.pkg} " +
                   "versionCode ${candidate.versionCode} (installed ${candidate.installedCode ?: "none"}), " +
                   candidate.evidence)
        // #1675 EVERY install, first or update, is a PackageInstaller session (USER_ACTION_NOT_REQUIRED, update ownership,
        // package source; serialised and throttled by the install gate). Never an intent on the APK: ACTION_VIEW resolved to
        // Android's "Open with" chooser on phones with a file handler, and it makes the system installer the installer of record.
        try {
            com.diegonmarcos.superapp.updater.install.UpdateInstaller(ctx).install(verified, candidate.pkg)
        } catch (_: com.diegonmarcos.superapp.updater.install.HandedToStore) { /* Cloud Store installs it */ }
        candidate
    }.onFailure { Log.w(TAG, "direct install ${app.id} failed: ${it.message}") }

    /** Pure (#1675): every install, first or update, goes through a session, never an intent on the APK. */
    @Suppress("UNUSED_PARAMETER")
    fun usesSession(installed: Boolean): Boolean = true

    /** Whether "Install unknown apps" is held. NOT a gate — the system asks for
     *  it inline when missing — but the UI should be able to warn first. */
    fun canRequestInstalls(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ctx.packageManager.canRequestPackageInstalls()
        else true

    private fun installedInfo(ctx: Context, app: Fleet.App): Pair<String?, Long>? {
        val pkg = Fleet.installedId(ctx, app) ?: return null
        return runCatching {
            @Suppress("DEPRECATION")
            val pi = ctx.packageManager.getPackageInfo(pkg, 0)
            pi.versionName to versionCodeOf(pi)
        }.getOrNull()
    }

    private fun archiveVersionName(ctx: Context, file: File): String? = runCatching {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.versionName
    }.getOrNull()

    /** The installed versionCode of [pkg], or null when it is not installed. */
    fun installedVersionCode(ctx: Context, pkg: String): Long? = try {
        @Suppress("DEPRECATION")
        versionCodeOf(ctx.packageManager.getPackageInfo(pkg, 0))
    } catch (_: PackageManager.NameNotFoundException) { null }

    private fun versionCodeOf(pi: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
        else @Suppress("DEPRECATION") pi.versionCode.toLong()
}

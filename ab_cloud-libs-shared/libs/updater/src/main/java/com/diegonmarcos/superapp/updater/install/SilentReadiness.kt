package com.diegonmarcos.superapp.updater.install

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import android.util.Log
import com.diegonmarcos.superapp.core.StoreNotifyGate
import java.io.File

/**
 * #1675 SILENT FLEET UPDATES THROUGH PUBLIC ANDROID APIS ONLY (no adb, no Shizuku, no root).
 *
 * Android installs an update with no dialog when the session asks for USER_ACTION_NOT_REQUIRED (API 31+), the installer
 * holds UPDATE_PACKAGES_WITHOUT_USER_ACTION and every one of these holds (checked here BEFORE a session is committed):
 *
 *  1. the package is already installed (a first install always asks);
 *  2. the installer is the package's INSTALLER OF RECORD (and, on 14+, its update owner, or there is none);
 *  3. the new APK targets at least the SDK the device demands ([minTargetSdk]);
 *  4. the update asks for no new dangerous (runtime) permission versus the installed copy;
 *  5. the installer has not committed a session for the same package within the throttle window ([THROTTLE_MS]).
 *
 * ONE INSTALLER OF RECORD (architect decision, #1675): Cloud Store, `com.diegonmarcos.cloudstore`. It already owns the fleet
 * pass (UpdateWorker.ownsFleetPass) and every Store notification (#894). Every other fleet app (the SuperApp launcher, the
 * per-app self-update in libs:updater, the dialer's and MyTerminal's own updaters) HANDS the request to Cloud Store instead of
 * opening its own session ([route]), so ownership is never split. The only local session outside Cloud Store is the bootstrap
 * case: Cloud Store is not installed yet (it cannot install itself before it exists).
 *
 * Everything here is pure except the [assess] reader, so the unit suite drives every branch.
 */
object SilentReadiness {

    private const val TAG = "Updater/Silent"

    /** The one installer of record of every fleet app. */
    const val INSTALLER_OF_RECORD: String = StoreNotifyGate.STORE_PKG

    /** At most one session per package per this window: Android throttles silent installs (~30 s per package). */
    const val THROTTLE_MS: Long = 30_000L

    /** The newest Android the fleet must meet (the owner's phone: Android 17 = API 37) and the floor it demands. */
    const val NEWEST_DEVICE_SDK = 37
    const val FLEET_MIN_TARGET_SDK = 35

    // ── installer of record ──────────────────────────────────────────────────────────────────────────────────

    enum class Route { LOCAL, HANDOFF }

    /**
     * Pure. Cloud Store installs locally; any other app hands off to Cloud Store when Cloud Store is installed. With no Cloud
     * Store on the phone the caller installs locally (bootstrap: that is how Cloud Store itself arrives).
     */
    fun route(selfPkg: String, storeInstalled: Boolean): Route =
        if (selfPkg == INSTALLER_OF_RECORD || !storeInstalled) Route.LOCAL else Route.HANDOFF

    /** Pure: is [selfPkg] the fleet's installer of record. Only it ever asks for update ownership. */
    fun isInstallerOfRecord(selfPkg: String): Boolean = selfPkg == INSTALLER_OF_RECORD

    // ── target SDK ───────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Pure: the lowest targetSdk an app may declare and still be updated silently on a device running [deviceSdk].
     * Android 12 (31/32) -> 29, 13 (33) -> 30, 14 (34) -> 31, 15 (35) -> 33, 16 (36) -> 34, 17 (37) and newer -> 35.
     * Below Android 12 there is no silent path at all, so there is no floor (0).
     */
    fun minTargetSdk(deviceSdk: Int): Int = when {
        deviceSdk < 31 -> 0
        deviceSdk <= 32 -> 29
        deviceSdk == 33 -> 30
        deviceSdk == 34 -> 31
        deviceSdk == 35 -> 33
        deviceSdk == 36 -> 34
        else -> 35
    }

    // ── new dangerous permissions ────────────────────────────────────────────────────────────────────────────

    /** Pure: the dangerous permissions [candidate] requests that [installed] does not, sorted. */
    fun newDangerousPermissions(installed: Set<String>, candidate: Set<String>, isDangerous: (String) -> Boolean): List<String> =
        (candidate - installed).filter(isDangerous).sorted()

    // ── throttle ─────────────────────────────────────────────────────────────────────────────────────────────

    /** Pure: how long to wait before the next session for a package last committed at [lastCommitMs] (null = never). */
    fun throttleWaitMs(lastCommitMs: Long?, nowMs: Long, windowMs: Long = THROTTLE_MS): Long =
        if (lastCommitMs == null) 0L else (lastCommitMs + windowMs - nowMs).coerceIn(0L, windowMs)

    // ── the install-channel ladder ───────────────────────────────────────────────────────────────────────────

    const val CHANNEL_SESSION = "session"
    const val CHANNEL_SHELL = "shell"

    /**
     * Pure: does Fleet.commit try the PackageInstaller session FIRST for this package. Yes when the native silent path holds
     * (or only the throttle stands in the way, which the session waits out): public APIs, no adb or Shizuku. Otherwise the
     * earlier ladder (the optional shell channel, then the confirmed session; "never prompt" keeps the shell only).
     */
    fun prefersSession(v: Verdict): Boolean = v.silent || v.reason == Reason.THROTTLE

    // ── the verdict ──────────────────────────────────────────────────────────────────────────────────────────

    enum class Reason(val silent: Boolean, val short: String) {
        SILENT(true, "silent"),
        OLD_ANDROID(false, "Android 11 or older"),
        TOGGLE_OFF(false, "silent updates off"),
        NOT_FLEET_SIGNED(false, "not fleet-signed"),
        FIRST_INSTALL(false, "first install"),
        NOT_INSTALLER_OF_RECORD(false, "not installer of record"),
        TARGET_SDK(false, "targetSdk too low"),
        NEW_PERMISSION(false, "new permission"),
        THROTTLE(false, "throttle"),
        HANDOFF(false, "handed to Cloud Store"),
    }

    /** What the next install of a package will do, and why, in one sentence for the Store row and the diagnostics. */
    data class Verdict(val reason: Reason, val detail: String) {
        val silent: Boolean get() = reason.silent
        /** "silent" or "will prompt: <reason>". */
        val line: String get() = if (silent) "silent" else "will prompt: $detail"
    }

    /** Everything [decide] needs, already read. A null field means "not known"; unknowns never block a silent verdict. */
    data class Facts(
        val deviceSdk: Int,
        val selfPkg: String,
        val silentOn: Boolean,
        val fleetSigned: Boolean,
        val installed: Boolean,
        /** installingPackageName (or the legacy installer name) of the installed copy. */
        val installer: String?,
        /** The 14+ update owner of the installed copy; null when none or before 14. */
        val updateOwner: String?,
        val candidateTargetSdk: Int?,
        val newPermissions: List<String>,
        val throttleWaitMs: Long,
    )

    /**
     * Pure. The FIRST unmet condition decides (most fundamental first), so the user is told the one thing to fix. A package
     * installed elsewhere (adb, a file manager, another store) is NOT_INSTALLER_OF_RECORD: Android asks once to transfer
     * ownership, then the next updates are silent. The throttle is reported but the caller waits it out instead of prompting.
     */
    fun decide(f: Facts): Verdict {
        val floor = minTargetSdk(f.deviceSdk)
        return when {
            f.deviceSdk < 31 -> Verdict(Reason.OLD_ANDROID, "Android 11 or older has no silent update")
            !f.silentOn -> Verdict(Reason.TOGGLE_OFF, "Silent updates is off in the Store settings")
            !f.fleetSigned -> Verdict(Reason.NOT_FLEET_SIGNED, "not signed by the fleet key, so it always asks")
            !isInstallerOfRecord(f.selfPkg) -> Verdict(Reason.HANDOFF, "this app is not the installer of record; Cloud Store installs it")
            !f.installed -> Verdict(Reason.FIRST_INSTALL, "first install: Android always asks once")
            f.installer != f.selfPkg || (f.updateOwner != null && f.updateOwner != f.selfPkg) ->
                Verdict(Reason.NOT_INSTALLER_OF_RECORD, "installed by ${f.installer ?: "an unknown source"}" +
                    (f.updateOwner?.takeIf { it != f.selfPkg }?.let { " (update owner $it)" } ?: "") +
                    ": Android asks once to move it to Cloud Store, then updates are silent")
            f.candidateTargetSdk != null && f.candidateTargetSdk < floor ->
                Verdict(Reason.TARGET_SDK, "targetSdk ${f.candidateTargetSdk} is below $floor, which Android API ${f.deviceSdk} requires for a silent update")
            f.newPermissions.isNotEmpty() ->
                Verdict(Reason.NEW_PERMISSION, "this update asks for new permission(s): " +
                    f.newPermissions.joinToString(", ") { it.substringAfterLast('.') })
            f.throttleWaitMs > 0 -> Verdict(Reason.THROTTLE, "another session for it ran under 30 s ago (waits ${(f.throttleWaitMs + 999) / 1000} s)")
            else -> Verdict(Reason.SILENT, "silent")
        }
    }

    // ── the reader (device state -> Facts) ───────────────────────────────────────────────────────────────────

    /** Last commit time per package, process-wide (the throttle). */
    private val lastCommit = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun lastCommitMs(pkg: String): Long? = lastCommit[pkg]
    fun markCommitted(pkg: String, nowMs: Long = System.currentTimeMillis()) { lastCommit[pkg] = nowMs }

    /** Read every fact for [pkg] (and its candidate [apk], when one is downloaded). Never throws. */
    fun assess(ctx: Context, pkg: String, apk: File?, nowMs: Long = System.currentTimeMillis()): Verdict = runCatching {
        val pm = ctx.packageManager
        val installedInfo = installedPackage(pm, pkg)
        val candidateInfo = apk?.let { archivePackage(pm, it) }
        val dangerous = { p: String -> isDangerous(pm, p) }
        val newPerms = if (installedInfo != null && candidateInfo != null)
            newDangerousPermissions(installedInfo.requestedPermissions?.toSet().orEmpty(),
                candidateInfo.requestedPermissions?.toSet().orEmpty(), dangerous)
        else emptyList()
        decide(Facts(
            deviceSdk = Build.VERSION.SDK_INT,
            selfPkg = ctx.packageName,
            silentOn = com.diegonmarcos.superapp.updater.AutoUpdatePrefs.silent(ctx),
            fleetSigned = SilentUpdate.isFleetSigned(ctx, pkg, apk),
            installed = installedInfo != null,
            installer = installerOf(ctx, pkg),
            updateOwner = SilentUpdate.updateOwner(ctx, pkg),
            candidateTargetSdk = candidateInfo?.applicationInfo?.targetSdkVersion
                ?: installedInfo?.applicationInfo?.targetSdkVersion,
            newPermissions = newPerms,
            throttleWaitMs = throttleWaitMs(lastCommitMs(pkg), nowMs),
        ))
    }.getOrElse {
        Log.w(TAG, "could not assess $pkg: ${it.message}")
        Verdict(Reason.NOT_INSTALLER_OF_RECORD, "could not read the install state (${it.javaClass.simpleName}); Android decides")
    }.also { remember(ctx, pkg, it) }

    /** The last verdict per package, so the Store row can show it without re-reading an APK. */
    fun lastVerdict(ctx: Context, pkg: String): String? = runCatching {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(pkg, null)
    }.getOrNull()

    private fun remember(ctx: Context, pkg: String, v: Verdict) {
        runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(pkg, v.line).apply() }
    }

    private const val PREFS = "silent_readiness"

    @Suppress("DEPRECATION")
    private fun installedPackage(pm: PackageManager, pkg: String): PackageInfo? =
        runCatching { pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS) }.getOrNull()

    @Suppress("DEPRECATION")
    private fun archivePackage(pm: PackageManager, apk: File): PackageInfo? =
        runCatching { pm.getPackageArchiveInfo(apk.path, PackageManager.GET_PERMISSIONS) }.getOrNull()

    private fun isDangerous(pm: PackageManager, perm: String): Boolean = runCatching {
        val info = pm.getPermissionInfo(perm, 0)
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.protection
                   else @Suppress("DEPRECATION") (info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE)
        base == PermissionInfo.PROTECTION_DANGEROUS
    }.getOrDefault(false)   // a permission the platform does not know (a custom one) is not a runtime prompt

    @Suppress("DEPRECATION")
    fun installerOf(ctx: Context, pkg: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ctx.packageManager.getInstallSourceInfo(pkg).installingPackageName
        else ctx.packageManager.getInstallerPackageName(pkg)
    }.getOrNull()

    /** Is Cloud Store installed on this phone. */
    fun storeInstalled(ctx: Context): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(INSTALLER_OF_RECORD, 0); true }.getOrDefault(false)
}

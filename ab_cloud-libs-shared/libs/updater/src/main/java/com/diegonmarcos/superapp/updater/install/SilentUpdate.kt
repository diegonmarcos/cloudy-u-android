package com.diegonmarcos.superapp.updater.install

import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * NATIVE SILENT UPDATES (Android 12+): the decision, pure, and the signer gate it depends on.
 *
 * The OS skips the confirmation only when the installer asks for USER_ACTION_NOT_REQUIRED AND is the update owner / installer of
 * record of the package. This asks for it only for FLEET-signed packages (same signing key as this app, which ships under the
 * constellation key): a package signed by anyone else gets the ordinary confirmed session, whatever the toggle says. Nothing here
 * works around the OS: a package this app does not own still shows its one dialog, and Android 11 or older has no native silent path.
 */
object SilentUpdate {

    /** What the session builder applies. A null field is left unset. */
    class Plan(
        val layer1: Boolean,
        val requireUserAction: Int?,
        val requestOwnership: Boolean,
        val packageSource: Int?,
        /** Why the silent path is not in play, as a sentence; null when it is. */
        val why: String?,
    )

    /**
     * Pure. Silent only for Android 12+ (S), the toggle on and a fleet-signed package. Ownership is requested only on the silent path
     * and only on 14+. The package source (STORE) is provenance, not silence, so it is set for every install on 13+ as before.
     */
    fun plan(sdk: Int, silentOn: Boolean, fleetSigned: Boolean, installerOfRecord: Boolean = true): Plan {
        val source = if (sdk >= Build.VERSION_CODES.TIRAMISU) PackageInstaller.PACKAGE_SOURCE_STORE else null
        val why = when {
            sdk < Build.VERSION_CODES.S -> "Android 11 or older has no native silent update"
            !silentOn -> "Silent updates is off"
            !fleetSigned -> "not signed by the fleet key"
            else -> null
        }
        if (sdk < Build.VERSION_CODES.S) return Plan(false, null, false, source, why)
        val silent = why == null
        return Plan(
            layer1 = silent,
            requireUserAction = if (silent) PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                                else PackageInstaller.SessionParams.USER_ACTION_REQUIRED,
            // #1675 only the fleet's ONE installer of record (Cloud Store) asks for update ownership; any other app asking would
            // take ownership away from it and turn every later Store update into a prompt.
            requestOwnership = silent && installerOfRecord && sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            packageSource = source,
            why = why,
        )
    }

    /** Pure: the same non-empty signer set. */
    fun sameSigner(own: Set<String>?, other: Set<String>?): Boolean = !own.isNullOrEmpty() && own == other

    /**
     * Pure gate over already-read signer sets: the installed copy decides when there is one (a foreign installed copy is never
     * overridden by a matching APK); a fresh install is judged on the candidate APK.
     */
    fun fleetSigned(own: Set<String>?, installed: Set<String>?, installedPresent: Boolean, candidate: Set<String>?): Boolean =
        if (installedPresent) sameSigner(own, installed) else sameSigner(own, candidate)

    /** SHA-256 of every signer of the INSTALLED [pkg]; null when it is not installed or unreadable. */
    @Suppress("DEPRECATION")
    internal fun installedSigners(pm: PackageManager, pkg: String): Set<String>? = runCatching {
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
        else pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        sigs?.map { sha256(it.toByteArray()) }?.toSet()
    }.getOrNull()

    /** The same for an APK file not yet installed. */
    @Suppress("DEPRECATION")
    internal fun archiveSigners(pm: PackageManager, apk: File): Set<String>? = runCatching {
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)?.signingInfo?.apkContentsSigners
        else pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)?.signatures
        sigs?.map { sha256(it.toByteArray()) }?.toSet()
    }.getOrNull()

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Is [targetPackage] (or, when not installed, [apk]) signed with the same key as this app? */
    fun isFleetSigned(ctx: Context, targetPackage: String, apk: File?): Boolean {
        val pm = ctx.packageManager
        val installed = installedSigners(pm, targetPackage)
        return fleetSigned(installedSigners(pm, ctx.packageName), installed, installed != null,
            if (installed == null && apk != null) archiveSigners(pm, apk) else null)
    }

    /** The package that owns updates of [pkg] (Android 14+), or null when none/unknown. */
    fun updateOwner(ctx: Context, pkg: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ctx.packageManager.getInstallSourceInfo(pkg).updateOwnerPackageName else null
    }.getOrNull()

    /** The card's lines on Store > Configs > ADB Shell: what native silent updates can do here, plainly. Pure. */
    fun statusLines(sdk: Int, silentOn: Boolean, ownerOfSelf: String?, selfPkg: String): List<String> {
        val p = plan(sdk, silentOn, fleetSigned = true)
        val owner = when {
            sdk < Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> "update owner: not tracked before Android 14"
            ownerOfSelf == selfPkg -> "update owner of this app: Cloud Store (updates of apps it owns are silent)"
            ownerOfSelf == null -> "update owner of this app: none yet (the first Store update asks once)"
            else -> "update owner of this app: $ownerOfSelf (the first Store update asks once)"
        }
        return listOf(
            "Android API $sdk: native silent updates " + if (p.layer1) "available for fleet apps" else "not available (${p.why})",
            owner,
            "Limits: a fleet app the Store does not own yet shows ONE confirmation on its first Store update, then updates silently. " +
                "Android 11 or older has no native silent update. Apps not signed by the fleet key are never installed silently.",
        )
    }
}

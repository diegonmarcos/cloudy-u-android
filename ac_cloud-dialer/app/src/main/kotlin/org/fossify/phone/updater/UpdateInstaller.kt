package org.fossify.phone.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Wraps PackageInstaller — Android's only no-root path to install an APK. The
 * user gets a system prompt to confirm; we can't bypass it without the system
 * updater privilege (F-Droid Privileged Extension territory).
 */
internal class UpdateInstaller(private val context: Context) {
    private val tag = "Updater/Install"

    fun install(apk: File, targetPackage: String = context.packageName) {
        UpdateProgress.update(UpdateProgress.State.Installing)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(targetPackage)
            // #1675 only when Cloud Store (the fleet's one installer of record) is absent: claiming ownership here would take it
            // away from Cloud Store and turn its silent updates into prompts.
            if (Build.VERSION.SDK_INT >= 34 && !Updater.storeInstalled(context)) {
                // Claim update ownership (Android 14+), same as the constellation's
                // other installers. Without it UPDATE_PACKAGES_WITHOUT_USER_ACTION is
                // inert whenever something else installed this app last, and every
                // update prompts again.
                setRequestUpdateOwnership(true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // #1675 NOT_REQUIRED: silent when Android's conditions hold (installer of record, targetSdk, no new runtime
                // permission), and Android itself falls back to its one dialog when they do not.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Without an explicit package source, Android 14/15 Enhanced
                // Confirmation Mode treats the install as untrusted-sideload and
                // RESTRICTS the installed app: Settings then refuses to grant it
                // protected roles (default Phone app / call screening) with the
                // "restricted setting" denial. Our GHCR fleet updater IS the
                // constellation's app store, so declare PACKAGE_SOURCE_STORE.
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
            }
        }
        // #1735 reap our stale sessions; on the platform's cap error reap harder and back off, never a blind retry.
        val sessionId = InstallSessions.open(InstallSessions.AndroidPort(installer, params))
        // Committed or abandoned on EVERY path (exception, timeout, thread interrupt between create and commit).
        var committed = false
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val callback = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(context, PackageInstallerReceiver::class.java).apply {
                        setPackage(context.packageName)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(callback.intentSender)
                committed = true
            }
        } finally {
            if (!committed) runCatching { installer.abandonSession(sessionId) }
                .onFailure { Log.w(tag, "could not abandon session $sessionId", it) }
        }
        // Session.close() only releases OUR handle; the finally above is what stops a failed install burning one of the 50 slots.
        Log.i(tag, "PackageInstaller session $sessionId committed for ${apk.name}")
    }

}

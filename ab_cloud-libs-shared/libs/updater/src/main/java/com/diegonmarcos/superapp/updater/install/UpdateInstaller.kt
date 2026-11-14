package com.diegonmarcos.superapp.updater.install

import com.diegonmarcos.superapp.updater.BuildConfig
import com.diegonmarcos.superapp.updater.InstallDiagnostics
import com.diegonmarcos.superapp.updater.ApkInstallWorker
import com.diegonmarcos.superapp.updater.AutoUpdatePrefs
import com.diegonmarcos.superapp.updater.PackageInstallerReceiver
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.Updater
import com.diegonmarcos.superapp.updater.VersionOrder
import com.diegonmarcos.superapp.updater.apk.ApkIntegrity
import com.diegonmarcos.superapp.updater.apk.VerifiedApk
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File

/**
 * The install was DECLINED before the session opened — the candidate is older
 * than what is installed, or there is not enough room to stage it.
 *
 * Its own type because the answer is permanent for these bytes and this phone:
 * no amount of retrying makes an old APK new or a full disk empty, so a worker
 * that treated every throw as transient would re-arm itself against a fact
 * that cannot change. Callers catch this to fail ONCE, with the reason already
 * on screen, instead of looping.
 */
internal class InstallRefused(message: String) : IllegalStateException(message)

/**
 * Wraps PackageInstaller — Android's only no-root path to install an APK.
 * The user gets a system prompt to confirm; we cannot bypass it without
 * the system updater permission (F-Droid Privileged Extension territory).
 */
internal class UpdateInstaller(private val context: Context) {
    private val tag = "Updater/Install"

    /**
     * Install [apk]. [targetPackage] is the applicationId of the APK being
     * installed — defaults to our own package for the self-update path, but
     * companion installs (Cloud-Comms / Cloud-IDE hubs) pass the foreign
     * package so PackageInstaller disambiguates correctly.
     */
    /**
     * Install [apk]. Blocks until the install settles.
     *
     * Serialised process-wide by [InstallGate]: commit() only HANDS OVER a
     * session and returns, so without this every caller that starts an install
     * on its own thread - the Constellation list's per-row button, a fleet
     * batch, ApkInstallWorker - would have theirs running concurrently.
     * Enforcing it HERE rather than at each call site is the point: a new
     * caller cannot forget to.
     */
    fun install(apk: VerifiedApk, targetPackage: String = context.packageName) {
        Log.i(tag, "install ${apk.file.name} → $targetPackage [${apk.evidence}]")
        // #1675 ONE INSTALLER OF RECORD: any app other than Cloud Store hands the install to Cloud Store (when it is installed)
        // instead of opening a session of its own, which would make it the package's installer and every later Store update a
        // prompt. This is the single choke every path reaches (batch, row button, ApkInstallWorker, self-update).
        StoreHandoff.handOff(context, targetPackage)?.let { h ->
            InstallDiagnostics.recordSession(context, targetPackage, "handed off", h.message.orEmpty())
            throw h
        }
        InstallGate.serialised(targetPackage, InstallGate.SETTLE_MS) {
            installLocked(apk.file, targetPackage, apk.splits.map { it.file })
        }
    }

    private fun installLocked(apk: File, targetPackage: String, splits: List<File> = emptyList()) {
        UpdateProgress.update(UpdateProgress.State.Installing)
        refuseDowngrade(apk, targetPackage)
        // ASK ABOUT SPACE BEFORE SPENDING AN INSTALL ATTEMPT ON IT.
        //
        // The bytes are already on disk once — the download lands in cacheDir —
        // and committing stages a SECOND copy under /data before the system
        // builds a third to install from. Both live on the same volume, so at
        // the moment of commit a 267 MB Collabora Office wants roughly 267 MB
        // of free space just to stage, and about 534 MB to see the install
        // through, on a phone that has only just spent 267 MB of mobile data
        // getting here. Short of that, openWrite dies on ENOSPC or the system
        // answers STATUS_FAILURE_STORAGE — neither of which names either
        // number, and the second only arrives asynchronously. Ask now, and say
        // both numbers, while there is still something useful to say.
        //
        // Only the STAGING shortfall is fatal enough to refuse on: below it the
        // install provably cannot start. An unreadable free-space figure (-1)
        // must never block an install that might have worked.
        val expected = apk.length()
        val free = freeStagingBytes()
        if (free in 0 until expected) {
            // refuse(), not a bare error(): this throw happens BEFORE commit, so
            // PackageInstaller never runs and the receiver that normally puts a
            // failure in front of the user never fires. Thrown silently it left
            // the screen reading "Installing…" for an install that had already
            // been declined — the same shape of invisible outcome the receiver
            // exists to end, one step upstream of it.
            refuse(targetPackage, apk,
                "not enough free space to install $targetPackage: the APK is " +
                "${expected / 1_000_000} MB and the install session has to stage a second " +
                "copy of it, but only ${free / 1_000_000} MB is free. Free about " +
                "${(2 * expected) / 1_000_000} MB and try again — the download is already on " +
                "disk, so retrying will not fetch it a second time")
        }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            // TELL THE SYSTEM HOW BIG THIS IS, BEFORE IT OPENS THE SESSION.
            //
            // Left unset, sizeBytes stays -1 and the platform reserves nothing
            // and evicts nothing: it uses this figure to free cache space
            // ahead of the staging write. At the 6-33 MB the rest of the fleet
            // ships at there is always enough slack that its absence never
            // showed. A 267 MB APK is the one that needs the eviction to
            // actually happen. openWrite's own length argument sizes the FILE
            // inside the session, which is a different question and cannot
            // stand in for this one.
            setSize(expected + splits.sumOf { it.length() })
            // NO setAppPackageName: it's only a hint, and forcing our fork id on
            // a resigned STOCK upstream APK (chat=com.mattermost.rnbeta,
            // matrix=io.element.android.x) makes PackageInstaller reject it with
            // INSTALL_FAILED_INVALID_APK. Let the APK declare its own package —
            // correct for self-update + patched forks + stock upstream alike.
            // Layer 1, decided by the pure SilentUpdate.plan: NOT_REQUIRED + update ownership only for a FLEET-signed package
            // with the toggle on (Android 12+/14+); any other package is asked for the ordinary confirmed session. The OS still
            // decides: a package this app does not own yet shows ONE dialog on its first Store update, then updates silently.
            // Without the "install unknown apps" grant NOT_REQUIRED degrades to a prompt on its own.
            val plan = SilentUpdate.plan(Build.VERSION.SDK_INT, AutoUpdatePrefs.silent(context),
                SilentUpdate.isFleetSigned(context, targetPackage, apk),
                installerOfRecord = SilentReadiness.isInstallerOfRecord(context.packageName))
            Log.i(tag, "silent plan for $targetPackage: ${if (plan.layer1) "native silent" else plan.why}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) plan.requireUserAction?.let { setRequireUserAction(it) }
            if (plan.requestOwnership && Build.VERSION.SDK_INT >= 34) setRequestUpdateOwnership(true)
            // Without an explicit package source, Android 14/15 Enhanced Confirmation Mode treats the install as an untrusted
            // sideload and RESTRICTS the installed app; our GHCR fleet updater IS the constellation's app store.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) plan.packageSource?.let { setPackageSource(it) }
        }
        // #1675 every Android condition for a silent update, checked BEFORE the session: installer of record, targetSdk, new
        // dangerous permissions, the per-package throttle. A throttle is waited out (one session per package per 30 s, inside
        // the process-wide install lock, so the queue is spaced, never doubled up); anything else is said, then the session
        // still runs and Android shows its one dialog, never a silent failure.
        var verdict = SilentReadiness.assess(context, targetPackage, apk)
        if (verdict.reason == SilentReadiness.Reason.THROTTLE) {
            val wait = SilentReadiness.throttleWaitMs(SilentReadiness.lastCommitMs(targetPackage), System.currentTimeMillis())
            Log.i(tag, "throttle: waiting ${wait} ms before the next session for $targetPackage")
            if (wait > 0) Thread.sleep(wait)
            verdict = SilentReadiness.assess(context, targetPackage, apk)
        }
        Log.i(tag, "silent readiness for $targetPackage: ${verdict.line}")
        if (verdict.reason == SilentReadiness.Reason.NEW_PERMISSION)
            UpdateProgress.update(UpdateProgress.State.Waiting("$targetPackage ${verdict.line}. Android will ask you to confirm it."))
        InstallDiagnostics.recordSession(context, targetPackage, "committing", verdict.line)
        supersedeOlderSessions(installer, targetPackage)
        // #1735 reap our stale sessions, and on the platform's cap error reap harder and back off (never a blind retry).
        val port = InstallSessions.AndroidPort(installer, params)
        val sessionId = InstallSessions.open(port, keep = setOf(targetPackage),
            onSuperseded = { SupersededSessions.add(context, it) }, onUndo = { SupersededSessions.consume(context, it) })
        // #1735 committed or abandoned on EVERY path: the abandon is in a finally, so an exception, a timeout or a
        // cancellation (thread interrupt) between create and commit cannot leave the session open.
        var committed = false
        try {
            SilentReadiness.markCommitted(targetPackage)
            installer.openSession(sessionId).use { session ->
                // Declare the length ONCE and re-check it after the copy. The
                // session is told to expect exactly this many bytes; if the file
                // shrinks or vanishes mid-write — PackageInstallerReceiver deletes
                // these by path once an install is confirmed, and external storage
                // can fill — the session ends up holding a short base.apk and the
                // system parser reports INSTALL_PARSE_FAILED_NOT_APK / "failed to
                // load asset path". That reads like a corrupt build and sends you
                // to the artifact, which is where an hour goes.
                // `expected` is the length read ONCE above, the same figure the
                // session was sized with — re-reading it here would let the
                // session's declared size and the write's disagree.
                var written = 0L
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, expected).use { output ->
                        written = input.copyTo(output)
                        session.fsync(output)
                    }
                }
                if (written != expected || apk.length() != expected) {
                    session.abandon()
                    error("staged $written of $expected bytes for $targetPackage " +
                        "(source now ${apk.length()}) — APK truncated or removed mid-install, " +
                        "not a bad build")
                }
                // Google Play's delivery is a base plus splits, and they install
                // as ONE session or not at all. Each split was verified by its
                // own digest before it reached this list (VerifiedApk.withSplits).
                splits.forEachIndexed { i, part ->
                    val len = part.length()
                    var copied = 0L
                    part.inputStream().use { input ->
                        session.openWrite("split$i-${part.name.substringAfterLast('-')}", 0, len).use { output ->
                            copied = input.copyTo(output)
                            session.fsync(output)
                        }
                    }
                    if (copied != len) {
                        session.abandon()
                        error("staged $copied of $len bytes of split ${part.name} for $targetPackage — truncated mid-install")
                    }
                }
                val callback = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(context, PackageInstallerReceiver::class.java).apply {
                        setPackage(context.packageName)
                        // The receiver deletes this file once the install is
                        // CONFIRMED. Carrying the exact path beats having the
                        // receiver re-derive it: it needs no fleet lookup (the
                        // fleet list lives in the app's BuildConfig, not this
                        // module's) and it cannot delete the wrong APK.
                        putExtra(PackageInstallerReceiver.EXTRA_APK_PATH, apk.absolutePath)
                        // The gate is keyed by the package we ARMED. Android's
                        // EXTRA_PACKAGE_NAME is not populated on every status,
                        // and a blank one would leave the batch waiting out the
                        // full timeout for an event it already received.
                        putExtra(PackageInstallerReceiver.EXTRA_TARGET_PKG, targetPackage)
                        // #1483 who asked for this session, fixed at commit: a session an unattended pass committed never opens the
                        // confirmation dialog, even if its answer lands while the app is on screen (the pass flag is cleared by then)
                        putExtra(PackageInstallerReceiver.EXTRA_UNATTENDED,
                            runCatching { com.diegonmarcos.superapp.updater.AutoUpdatePrefs.unattendedPass(context) }.getOrDefault(false))
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
        // Session.close() (what `use` does) only releases OUR handle - the session stays alive in the system until it is
        // committed or abandoned, and survives reboots. The finally above is what keeps every failed install from burning
        // one of the 50 slots Android allows an installer without INSTALL_PACKAGES ("Too many active sessions for UID").
        Log.i(tag, "PackageInstaller session $sessionId committed for ${apk.name}")
    }

    /**
     * REFUSE AN OLDER BUILD THAN THE ONE ALREADY ON THE PHONE.
     *
     * The update signal in this fleet is a DIGEST: the check asks whether
     * GHCR's APK hashes differently from the installed one. A digest is an
     * identity, not an ordering — "different" is equally "newer" and "older" —
     * so anything that republishes a stale artefact under the moving tag reads
     * as an update and gets installed over a newer build. A mirror pin that
     * never moved did exactly that to the store, at the level above this one.
     *
     * The comparison here is the APK's OWN declared versionCode, read out of
     * the candidate's binary manifest before a byte is staged, against what
     * PackageManager reports for the package on the device. Both numbers come
     * from the artefacts themselves rather than from a pin, a sidecar or a
     * checksum recorded elsewhere, which is what makes them trustworthy: a
     * pinned value can disagree with the bytes it names, and in this fleet it
     * has. The ship engine writes that number as 3,000,000 + minutes since
     * 2026-01-01, so it is monotonic in wall-clock time by construction and
     * ordering it is meaningful rather than conventional.
     *
     * STRICTLY older is refused; EQUAL is allowed. A same-version reinstall is
     * the normal outcome of a non-reproducible rebuild, and it is exactly what
     * the digest check legitimately fires on — refusing it would block the
     * ordinary case in the name of the rare one.
     *
     * Two unknowns, both allowed through on purpose. An unparseable candidate
     * and an absent installed package each mean "no ordering exists", not "the
     * ordering is wrong", and refusing an install over a number we failed to
     * obtain is its own unexplained failure. Android's own check
     * (INSTALL_FAILED_VERSION_DOWNGRADE) still sits behind this one; the point
     * of doing it here is to answer BEFORE staging a quarter-gigabyte, and to
     * answer in a sentence rather than as an asynchronous error code.
     */
    private fun refuseDowngrade(apk: File, targetPackage: String) {
        val candidate = ApkIntegrity.identify(context, apk) ?: run {
            Log.w(tag, "cannot read a versionCode out of ${apk.name} — no ordering to check")
            return
        }
        val installed = installedVersionCode(targetPackage) ?: return
        // Asked of VersionOrder rather than spelled out here, because Fleet.commit
        // asks the SAME question about the fleet's other apps and two copies of
        // "is this a downgrade" are two chances to disagree about the boundary.
        // The one that matters is equal: a same-versionCode rebuild must still
        // install, because that is how a damaged install is repaired. VersionOrder
        // is driven through all four orderings by a unit test; this line was not
        // reachable by one.
        if (!VersionOrder.isDowngrade(candidate.versionCode, installed)) return
        refuse(targetPackage, apk,
            "refused to install an older $targetPackage over a newer one: the published " +
            "APK declares versionCode ${candidate.versionCode} and this phone already has " +
            "$installed. Nothing was installed and nothing was replaced. This is a problem " +
            "with what was published, not with this phone — the update will arrive on its " +
            "own once a newer build is published")
    }

    /** The installed versionCode of [pkg], or null when it is not installed. */
    private fun installedVersionCode(pkg: String): Long? = runCatching {
        val info = context.packageManager.getPackageInfo(pkg, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrNull()

    /**
     * Decline an install and SAY SO, then throw.
     *
     * Everything downstream of commit() reports itself through
     * [PackageInstallerReceiver]. A refusal upstream of commit() has no such
     * channel, so it publishes its own terminal state — otherwise the only
     * difference between "we decided not to" and "it hung" is a logcat line
     * the owner cannot read.
     */
    private fun refuse(targetPackage: String, apk: File, why: String): Nothing {
        Log.w(tag, why)
        UpdateProgress.update(UpdateProgress.State.Failed(
            why, appId = targetPackage, pkg = targetPackage, apkPath = apk.absolutePath))
        throw InstallRefused(why)
    }

    /**
     * Free bytes on the volume an install session stages into.
     *
     * cacheDir and the session's staging area are both on /data, which is
     * exactly why the downloaded APK and its staged copy compete for the same
     * space instead of the download being free once it has landed.
     *
     * -1 when it cannot be read: an unknown figure must never be treated as a
     * shortage, because refusing an install over a number we failed to obtain
     * is its own kind of unexplained failure.
     */
    private fun freeStagingBytes(): Long = runCatching {
        val stat = android.os.StatFs(context.cacheDir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrDefault(-1L)

    /**
     * How many new install sessions it is safe to open right now.
     *
     * Reaps first, then reports the remaining headroom under [NEAR_CAP]. A
     * background pass asks this before deciding how many apps to install,
     * because the sessions it opens are NOT short-lived: it cannot show the
     * system install dialog (Android 10+ blocks background activity starts), so
     * each one becomes a tap-to-install notification that holds its session
     * until the user answers it. Capping the batch alone would not be enough -
     * passes repeat every few hours and on every launch, so without a budget
     * the held sessions still creep up to Android's 50-session limit.
     */
    internal fun freeSessionSlots(): Int {
        val installer = context.packageManager.packageInstaller
        reapStaleSessions(installer)
        val held = runCatching { installer.mySessions.size }.getOrElse { return 0 }
        return (NEAR_CAP - HEADROOM - held).coerceAtLeast(0)
    }

    /** #1735 Reap our stale sessions (policy and why: [InstallSessions]). Uncommitted leftovers only; the cap path is in [InstallSessions.open]. */
    private fun reapStaleSessions(installer: PackageInstaller) {
        InstallSessions.reap(InstallSessions.AndroidPort(installer), System.currentTimeMillis(), aggressive = false)
    }

    /**
     * #1551 ONE LIVE SESSION PER PACKAGE. Every pass that found an app still
     * waiting on its confirmation used to commit ANOTHER session for it, so a
     * phone with auto-update on piled up duplicates until the cap reaper
     * abandoned them. Before a new session for [targetPackage] opens, our older
     * committed, unfinished sessions for the same package are abandoned and
     * recorded in [SupersededSessions], so their abort answer is swallowed by
     * the receiver instead of being reported as this install failing. The new
     * session surfaces its own fresh confirmation.
     */
    private fun supersedeOlderSessions(installer: PackageInstaller, targetPackage: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val mine = runCatching { installer.mySessions }.getOrNull().orEmpty()
        for (info in mine) {
            if (info.appPackageName != targetPackage || !info.isCommitted || info.isActive) continue
            SupersededSessions.add(context, info.sessionId)
            if (runCatching { installer.abandonSession(info.sessionId) }.isSuccess)
                Log.i(tag, "superseded session ${info.sessionId} for $targetPackage by a new one")
            else SupersededSessions.consume(context, info.sessionId)
        }
    }

    private companion object {
        const val NEAR_CAP = InstallSessions.NEAR_CAP
        const val HEADROOM = InstallSessions.HEADROOM
    }
}

package com.diegonmarcos.superapp.updater

import com.diegonmarcos.superapp.updater.cache.ApkCache
import com.diegonmarcos.superapp.updater.install.InstallFailure
import com.diegonmarcos.superapp.updater.install.InstallGate
import com.diegonmarcos.superapp.updater.install.InstallRetryHook
import com.diegonmarcos.superapp.updater.install.SupersededSessions
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.diegonmarcos.superapp.core.FleetAlerts
import com.diegonmarcos.superapp.core.NotificationStore
import com.diegonmarcos.superapp.core.SilentChannels
import com.diegonmarcos.superapp.core.StoreNotifyGate
import com.diegonmarcos.superapp.core.StoreNotifyPrefs

/**
 * Receives PackageInstaller status callbacks. Forwards the system
 * confirmation Activity on STATUS_PENDING_USER_ACTION; surfaces SUCCESS /
 * FAILURE_* outcomes as a Toast + persistent notification so silent
 * rejects (e.g. signature mismatch after a keystore bump) stop being
 * invisible.
 *
 * THE INVARIANT: NO STATUS LEAVES THIS RECEIVER WITHOUT SAYING SOMETHING.
 *
 * Every branch of the `when` below either shows the user a message or puts a
 * tappable notification in front of them, and the `else` names every status
 * Android defines rather than printing its number. The owner cannot read
 * logcat for another app on this phone, so a log line is not a report; an
 * install that fails without telling anyone costs a repeat of the download,
 * and for Cloud Office that download is 267 MB of mobile data.
 */
class PackageInstallerReceiver : BroadcastReceiver() {
    private val TAG = "Updater/Receiver"
    private val NOTIF_CHANNEL = UPDATER_CHANNEL
    private val NOTIF_ID = UPDATER_NOTIF_ID

    /**
     * Delete the cached APK once the install is CONFIRMED successful — and
     * #625: only once the CACHED ARTIFACT is confirmed to be what is now
     * INSTALLED.
     *
     * A success status was never quite the right gate. It says the installer
     * finished without complaining; it does not say the bytes we are about to
     * throw away are the bytes that landed. [ApkCache.reapIfInstalled] asks the
     * device instead: the file must still hash to what was downloaded, and the
     * installed package must match that artifact's own package name,
     * versionCode and signing certificate. Anything less keeps the file, with
     * the reason. What cannot be proven without root — that the installed
     * base.apk is byte-identical — is not claimed; see [ApkCache]'s header.
     *
     * The "only our own cache" check moved into [ApkCache.isOurs] so it follows
     * the directory. It used to compare against `context.cacheDir`, and left
     * there it would have matched nothing after the move — a reap that silently
     * stops happening, which has no symptom except a cache that never shrinks.
     *
     * Best-effort about deleting, strict about deciding: a failed delete costs
     * disk, a wrong decision costs the user the whole download.
     */
    private fun reapCachedApk(context: Context, path: String?) {
        if (path.isNullOrEmpty()) return
        when (val r = ApkCache.reapIfInstalled(context, java.io.File(path))) {
            is ApkCache.Retention.Reaped ->
                Log.i(TAG, "reaped cached ${r.name}, freed ${r.freedBytes / 1_000_000}MB: ${r.reason}")
            is ApkCache.Retention.Kept ->
                Log.i(TAG, "cache KEPT after a success status: ${r.reason}")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, NO_STATUS)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        // EXTRA_STATUS is a coarse bucket — STATUS_FAILURE tells you nothing
        // about WHY. The legacy code underneath it is the exact reason
        // (INSTALL_PARSE_FAILED_NOT_APK, INSTALL_FAILED_UPDATE_INCOMPATIBLE,
        // …), and the platform hands it to us in every result. We were reading
        // past it, so the one precise fact in the whole exchange was thrown
        // away and the search moved to logcat — where it can never be, because
        // PackageParser logs under system_server's uid and an app may only
        // read its own. The detail was always here, not there.
        val legacy = intent.getIntExtra("android.content.pm.extra.LEGACY_STATUS", Int.MIN_VALUE)
        val legacyName = legacyStatusName(legacy)
        // For a CONFLICT this names the package we collided with, which is the
        // difference between "signature mismatch" and "someone else owns it".
        val other = intent.getStringExtra(PackageInstaller.EXTRA_OTHER_PACKAGE_NAME).orEmpty()
        // Which app was being installed — our own (self-update) or a companion
        // (Cloud-Comms / Cloud-IDE hub). Drives an accurate success message.
        val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME).orEmpty()
        val appName = when {
            pkg.isBlank() || pkg == context.packageName -> "Cloud SuperApp"
            else -> pkg
        }
        // This same receiver drives installs AND uninstalls (the long-press
        // menu fires PackageInstaller.uninstall with op=uninstall). Uninstall
        // shows no in-app overlay, so it must NOT touch UpdateProgress.
        val isUninstall = intent.getStringExtra(EXTRA_OP) == OP_UNINSTALL
        // Prefer the package we armed the gate with; EXTRA_PACKAGE_NAME is a
        // fallback for sessions started outside a batch.
        val gateKey = intent.getStringExtra(EXTRA_TARGET_PKG).orEmpty().ifEmpty { pkg }
        // Which app anything the USER reads is about. [appName] above reads
        // EXTRA_PACKAGE_NAME, which the platform leaves empty on
        // STATUS_PENDING_USER_ACTION — so every confirmation prompt for a
        // companion app announced itself as "Cloud SuperApp". gateKey is the
        // package we armed the session with, and it is always present.
        val subject = when {
            gateKey.isBlank() || gateKey == context.packageName -> "Cloud SuperApp"
            else -> gateKey
        }
        // NOTE: the gate is NOT opened here. Releasing on every status - which
        // this used to do - released it on STATUS_PENDING_USER_ACTION too, so
        // the next install committed while the current one's dialog was still
        // on screen. With Play Protect adding a second dialog on top of the
        // install confirm, that reliably collapsed to one successful install
        // per run. Each branch below decides for itself.
        fun releaseGate() {
            if (!isUninstall && gateKey.isNotEmpty()) InstallGate.open(gateKey)
        }
        val verb = if (isUninstall) "Uninstall" else "Install"
        // #894 which build this result is about, read BEFORE a success reaps the APK. A result
        // belonging to an unattended pass is filed under the pass's ONE summary alert (PassLedger)
        // and raises nothing of its own; one outside a pass raises a failure once, never a success.
        val build = if (isUninstall) "0" else (intent.getStringExtra(EXTRA_APK_PATH)
            ?.let { runCatching { com.diegonmarcos.superapp.updater.apk.ApkIntegrity.identify(context, java.io.File(it))?.versionCode }.getOrNull() } ?: 0L).toString()
        fun filed(outcome: PassLedger.Outcome, reason: String = ""): Boolean =
            !isUninstall && gateKey.isNotEmpty() && PassLedgerStore.record(context, outcome, gateKey, build, reason)
        Log.i(TAG, "status=$status msg=$message pkg=$pkg op=${if (isUninstall) "uninstall" else "install"}")
        // #1551 A session this app replaced with a newer one for the same package: its abort is not news. Swallowed before the
        // gate, the note or a notification is touched, because all three now belong to the newer session.
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION && status != PackageInstaller.STATUS_SUCCESS &&
            SupersededSessions.consume(context, sessionId)) {
            Log.i(TAG, "session $sessionId for $gateKey was superseded by a newer one - its abort is expected")
            return
        }

        // Install results arrive asynchronously, and the last few can land
        // AFTER Fleet.autoPass has cleared UpdateProgress.quiet in its finally.
        // Those late callbacks were the second way a background pass reached
        // the screen: an overlay flashing "Done" for an app the user never
        // asked about. The persisted pass flag outlives the field precisely so
        // this edge is covered.
        val unattended = runCatching { AutoUpdatePrefs.unattendedPass(context) }.getOrDefault(false)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    // PENDING_USER_ACTION carrying no Intent means the system
                    // wants a confirmation it has given us no way to ask for,
                    // so this session can never proceed. This used to be
                    // `releaseGate(); return` — no toast, no notification, no
                    // state change — which left the progress row reading
                    // "Installing…" forever for an install that was already
                    // over. That is precisely the shape of "it downloads the
                    // whole 267 MB and then nothing happens and nothing says
                    // why": not a failure that was reported badly, a failure
                    // that was never reported at all.
                    releaseGate()
                    Log.w(TAG, "PENDING_USER_ACTION with no EXTRA_INTENT for $gateKey")
                    if (!isUninstall && !unattended) UpdateProgress.update(
                        UpdateProgress.State.Failed(
                            "Android asked for your confirmation and supplied no screen to " +
                            "ask it on — nothing was installed",
                            appId = gateKey,
                            pkg = gateKey,
                            apkPath = intent.getStringExtra(EXTRA_APK_PATH).orEmpty(),
                        ))
                    val inPass = filed(PassLedger.Outcome.FAILED, "confirmation offered no screen")
                    surface(context, "$verb needs confirmation, but none was offered",
                        "$subject could not be ${verb.lowercase()}ed: the system asked for " +
                        "your confirmation and then supplied no screen to ask it on. Nothing " +
                        "was installed. The downloaded file is kept — try the row's Direct " +
                        "button, which uses the ordinary system installer.",
                        severity = NotificationStore.Sev.ERROR, key = "${verb.lowercase()}:$gateKey",
                        quiet = inPass || !isNew(context, gateKey, build, "no confirmation screen"))
                    return
                }
                if (!isUninstall && gateKey.isNotEmpty()) ApkCache.installerStatus(context, gateKey,
                    PENDING_STATUS_TEXT)
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // POST THE NOTIFICATION FIRST, ALWAYS. THEN TRY THE DIALOG.
                //
                // Android 10+ BLOCKS a background activity start and does NOT
                // throw: startActivity silently no-ops. [isForeground] used to
                // be the entire defence against that, and it is a SAMPLE taken
                // when the broadcast lands, not a guarantee held across the
                // call — so the probe could answer "foreground", the process
                // could be backgrounded a moment later, the dialog could never
                // appear, and there was nothing behind it. Staging a 267 MB
                // APK takes long enough that the user has usually left the
                // screen by the time this status arrives, which is why the
                // biggest app in the fleet lost this race the most reliably.
                //
                // So the notification is the GUARANTEE and the dialog is the
                // optimisation. A confirmation that turns out to be redundant
                // costs one stale notification, and the terminal status below
                // clears it; a confirmation that is merely hoped for costs the
                // entire download.
                val shown = notifyConfirm(context, confirm, subject, gateKey)
                val inPass = filed(PassLedger.Outcome.NEEDS_TAP)
                if (isForeground(context)) {
                    // The dialog is going up NOW and this install has not
                    // finished. Keep the gate SHUT: the next commit must wait
                    // for the user to answer, or its dialog stacks on top of
                    // this one and Play Protect's scan prompt lands between
                    // them. The gate's timeout is the backstop if the user
                    // simply walks away.
                    runCatching { context.startActivity(confirm) }.onFailure {
                        Log.w(TAG, "confirm dialog refused to start for $gateKey", it)
                    }
                } else {
                    // Background: we cannot show a dialog, so this session now
                    // waits on the notification posted above. Release -
                    // otherwise one unanswered notification wedges every later
                    // install until the timeout. What bounds these is the
                    // per-pass cap, not the gate.
                    Log.w(TAG, "confirm launch deferred to notification (app backgrounded)")
                    releaseGate()
                    // #588: "deferred to notification" assumed the notification
                    // EXISTS. With notifications off for this app (Android 13+
                    // POST_NOTIFICATIONS denied, or the Updater channel muted)
                    // nm.notify() is a silent no-op, so the install waited on a
                    // prompt nobody could ever see and the row claimed there was
                    // one to tap. Say what is true instead: committed, blocked,
                    // and the one setting that unblocks it.
                    if (!shown) {
                        Log.w(TAG, "confirm notification for $gateKey cannot be shown — notifications are off")
                        if (!isUninstall && !unattended) UpdateProgress.update(UpdateProgress.State.Failed(
                            "$subject is downloaded but waiting for a confirmation this phone " +
                            "cannot show: notifications are off for this app. Allow them " +
                            "(Settings ▸ Apps ▸ Notifications), or open the app and retry",
                            appId = gateKey,
                            pkg = gateKey,
                            apkPath = intent.getStringExtra(EXTRA_APK_PATH).orEmpty(),
                        ))
                        surface(context, "$verb is waiting on a hidden confirmation",
                            "$subject was not installed: Android needs one tap to confirm, and " +
                            "the notification carrying it is blocked because notifications are " +
                            "off for this app. Allow notifications, or open the app and retry.",
                            severity = NotificationStore.Sev.ERROR, key = "${verb.lowercase()}:$gateKey",
                            quiet = inPass || !isNew(context, gateKey, build, "notifications off"))
                        return
                    }
                }
                // The row says "Installing…" and would keep saying it for as
                // long as the user takes to answer — indistinguishable from a
                // stuck install. Name what is actually being waited on, so the
                // confirmation is still findable from the screen the user is
                // already looking at after the notification has been swiped
                // away. Waiting is deliberately not a full-screen overlay.
                if (!isUninstall && !unattended) UpdateProgress.update(
                    UpdateProgress.State.Waiting(
                        "$subject needs your confirmation — tap the \"tap to finish " +
                        "installing\" notification"))
            }
            PackageInstaller.STATUS_SUCCESS -> {
                releaseGate()   // terminal: the next install may start
                clearConfirmNotification(context, gateKey)
                // Drop the cached APK now that it is genuinely installed. This
                // is the ONLY point where success is known: commit() only means
                // the session was handed over, and a user who declines the
                // prompt still needs the bytes for a retry. Deleting earlier
                // would turn every declined dialog into a re-download.
                if (!isUninstall) reapCachedApk(context, intent.getStringExtra(EXTRA_APK_PATH))
                // #774 the row's truth: nothing failed, and anything older than
                // what is now installed can never be installed again.
                if (!isUninstall && gateKey.isNotEmpty()) {
                    ApkCache.clearNote(context, gateKey)
                    ApkCache.clearInstallerStatus(context, gateKey)
                    runCatching {
                        val pi = context.packageManager.getPackageInfo(gateKey, 0)
                        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
                                   else @Suppress("DEPRECATION") pi.versionCode.toLong()
                        ApkCache.pruneStale(context, gateKey, code)
                    }
                }
                // Resolve the install overlay (it sat on "Installing…" while the
                // system installer was up). MainActivity auto-dismisses on Done.
                // Uninstall never raised the overlay, so leave it alone.
                if (!isUninstall && !unattended) UpdateProgress.update(UpdateProgress.State.Done)
                val inPass = filed(PassLedger.Outcome.INSTALLED)
                if (!isUninstall && gateKey.isNotEmpty()) PassLedgerStore.clearFailure(context, gateKey)
                // A success is never an alert of its own: the pass summary counts it, a manual
                // install already shows its progress and a toast.
                surface(context, "${verb}ed ✓", "$appName ${verb.lowercase()}ed successfully.",
                    severity = NotificationStore.Sev.INFO, key = "${verb.lowercase()}:$gateKey",
                    quiet = inPass, raise = false)
            }
            else -> {
                releaseGate()   // terminal (failure/abort): unblock the queue
                clearConfirmNotification(context, gateKey)
                // EVERY status Android defines gets a sentence. The two added
                // here were reaching the user as the bare text "status=7" and
                // "status=-999", which names nothing and points nowhere.
                val label = when (status) {
                    PackageInstaller.STATUS_FAILURE             -> "FAILURE"
                    PackageInstaller.STATUS_FAILURE_ABORTED     -> "ABORTED"
                    PackageInstaller.STATUS_FAILURE_BLOCKED     -> "BLOCKED"
                    PackageInstaller.STATUS_FAILURE_CONFLICT    -> "CONFLICT (signature/applicationId mismatch — uninstall previous install once)"
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "INCOMPATIBLE"
                    PackageInstaller.STATUS_FAILURE_INVALID     -> "INVALID"
                    PackageInstaller.STATUS_FAILURE_STORAGE     -> "STORAGE (not enough free space to stage and install this APK)"
                    // API 34. The one a 267 MB APK can actually reach: the
                    // system bounds how long it will hold a session waiting,
                    // and a quarter-gigabyte stage is the only thing in this
                    // fleet slow enough to test that bound.
                    PackageInstaller.STATUS_FAILURE_TIMEOUT     -> "TIMEOUT (the system stopped waiting on this session before it finished)"
                    NO_STATUS -> "no status at all — the installer answered without saying what happened"
                    else -> "unrecognised installer status $status"
                }
                // Resolve the overlay to a visible failure instead of leaving it
                // stuck on "Installing…" for a companion install (installs only).
                // Carry the target and the staged file into the failure state:
                // this is where INSTALL_PARSE_FAILED_NOT_APK lands, and the
                // Diagnose screen can only describe the right app and the right
                // file if it is told which they were.
                // A failure in an unattended pass still surfaces — as an ERROR
                // notification from surface() below, which the in-app feed and
                // the Diagnose screen both read. What it must not do is seize
                // the screen for work the user never started.
                if (!isUninstall && !unattended) UpdateProgress.update(UpdateProgress.State.Failed(
                    listOfNotNull(
                        message.ifEmpty { label },
                        legacyName?.let { "code: $it" },
                        other.takeIf { it.isNotBlank() }?.let { "conflicts with: $it" },
                    ).joinToString(" · "),
                    appId = gateKey,
                    pkg = gateKey,
                    apkPath = intent.getStringExtra(EXTRA_APK_PATH).orEmpty(),
                ))
                // #774 THE CACHE OUTLIVES THIS, AND SO MUST THE REASON. Nothing
                // here deletes the staged APK — a cancelled sheet is the case
                // the cache exists for — and the Store row reads this note to
                // say "cached · install cancelled" with Install one tap away,
                // even after the process that heard it is gone.
                if (!isUninstall && gateKey.isNotEmpty()) ApkCache.note(context, gateKey,
                    ApkCache.STAGE_INSTALL, message.ifEmpty { label } +
                        (legacyName?.let { " · code: $it" } ?: ""))
                // Every extra, once, at W. Cheap, bounded, and it is OUR log so
                // the Diagnose screen picks it up — unlike the platform's.
                Log.w(TAG, "install failed pkg=$gateKey status=$status legacy=$legacy " +
                    "(${legacyName ?: "none"}) other=$other msg=${message.ifEmpty { "-" }} " +
                    "extras=${intent.extras?.keySet()?.joinToString(",") ?: "-"}")
                val inPass = filed(PassLedger.Outcome.FAILED, label)
                // #1551 A session that did not finish is retried from the cache by the queue (backoff, 30 s floor); it is
                // reported as a retry, and only the queue reports the final failure once its retries are used up.
                val why = message.ifEmpty { label } + (legacyName?.let { " · code: $it" } ?: "")
                val transient = !isUninstall && gateKey.isNotEmpty() && InstallFailure.transient(why)
                if (transient) runCatching { InstallRetryHook.onTransient?.invoke(context, gateKey, why) }
                    .onFailure { Log.w(TAG, "retry hand-off for $gateKey failed: ${it.message}") }
                surface(context, if (transient) "$verb did not finish, retrying: $label" else "$verb failed: $label",
                    message.ifEmpty { label },
                    severity = NotificationStore.Sev.ERROR, key = "${verb.lowercase()}:$gateKey",
                    quiet = inPass || (!isUninstall && !isNew(context, gateKey, build, label)))
            }
        }
    }

    /**
     * The legacy INSTALL_* constants, by value. Not exposed as public API, and
     * a bare "-100" in a toast is no better than no code at all, so the small
     * table earns its place: these are the failures a fleet installer actually
     * meets, and each one points somewhere different.
     */
    private fun legacyStatusName(code: Int): String? = when (code) {
        Int.MIN_VALUE -> null            // extra absent — nothing to report
        1 -> null                        // SUCCEEDED
        -1 -> "INSTALL_FAILED_ALREADY_EXISTS"
        -2 -> "INSTALL_FAILED_INVALID_APK"
        -3 -> "INSTALL_FAILED_INVALID_URI"
        -4 -> "INSTALL_FAILED_INSUFFICIENT_STORAGE"
        -5 -> "INSTALL_FAILED_DUPLICATE_PACKAGE"
        -7 -> "INSTALL_FAILED_UPDATE_INCOMPATIBLE (signature differs from the installed copy)"
        // SharedUidMigration (libs:appstore): the new build's android:sharedUserId differs from
        // the installed one's. Not retryable — the Store turns the row into the one-time migration.
        -8 -> "INSTALL_FAILED_SHARED_USER_INCOMPATIBLE"
        -9 -> "INSTALL_FAILED_MISSING_SHARED_LIBRARY"
        -12 -> "INSTALL_FAILED_OLDER_SDK"
        -15 -> "INSTALL_FAILED_TEST_ONLY"
        -20 -> "INSTALL_FAILED_MEDIA_UNAVAILABLE"
        -23 -> "INSTALL_FAILED_PACKAGE_CHANGED"
        -24 -> "INSTALL_FAILED_UID_CHANGED"
        -25 -> "INSTALL_FAILED_VERSION_DOWNGRADE"
        -29 -> "INSTALL_FAILED_DEPRECATED_SDK_VERSION"
        -100 -> "INSTALL_PARSE_FAILED_NOT_APK"
        -101 -> "INSTALL_PARSE_FAILED_BAD_MANIFEST"
        -102 -> "INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION"
        -103 -> "INSTALL_PARSE_FAILED_NO_CERTIFICATES"
        -104 -> "INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES"
        -105 -> "INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING"
        -108 -> "INSTALL_PARSE_FAILED_MANIFEST_MALFORMED"
        -110 -> "INSTALL_FAILED_INTERNAL_ERROR"
        -113 -> "INSTALL_FAILED_NO_MATCHING_ABIS"
        // #588: the code the phone actually reported, and the table did not
        // know it — it had -118 (BAD_SIGNATURE) labelled ABORTED instead.
        // "Session was abandoned" means our own reaper or a cancel ended the
        // session, never a bad APK.
        -115 -> "INSTALL_FAILED_ABORTED (the session was abandoned or cancelled before it finished)"
        -116 -> "INSTALL_FAILED_SESSION_INVALID"
        -117 -> "INSTALL_FAILED_BAD_DEX_METADATA"
        -118 -> "INSTALL_FAILED_BAD_SIGNATURE"
        -124 -> "INSTALL_PARSE_FAILED_RESOURCES_ARSC_COMPRESSED"
        else -> "legacy=$code"
    }

    /** True when THIS app's process is currently foreground — the only state in
     *  which a background-context startActivity is honoured on Android 10+.
     *  Anything else (WorkManager auto-update tick with no visible Activity)
     *  must route the confirm Intent through a notification instead. */
    private fun isForeground(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val mine = context.packageName
        return am.runningAppProcesses?.any {
            it.processName == mine &&
                it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        } ?: false
    }

    /** Drop the "tap to finish installing" notification once the session has
     *  reached a terminal state. It is an instruction to act on a session that
     *  no longer exists, and a stale one looks exactly like a live one — which
     *  would turn the guarantee added above into a new way to mislead. */
    private fun clearConfirmNotification(context: Context, pkg: String) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // #1551 only THIS package's prompt: one shared id let any app's outcome remove another app's pending prompt.
            nm.cancel(confirmId(pkg))
            nm.cancel(NOTIF_ID + 1)   // what builds before #1551 posted under the one shared id
        }
    }

    /** The durable surface for STATUS_PENDING_USER_ACTION: a tap-to-install
     *  notification wrapping the system confirm Intent. Posted on EVERY
     *  pending-user-action, foreground or not — notifications can launch
     *  activities from the background, which startActivity cannot, and unlike
     *  startActivity a notification that was posted is observably there.
     *
     *  Returns whether the user can actually SEE it: notify() on a blocked app
     *  or channel returns normally and shows nothing, so "posted" is not
     *  "shown" and the caller must not tell anyone to tap it (#588). */
    private fun notifyConfirm(context: Context, confirm: Intent, subject: String, pkg: String): Boolean =
        StoreNotifyGate.mayPost(context) && runCatching {
            // Configs ▸ Notifications: "update available" on the notification channel (default on).
            if (!StoreNotifyPrefs.allows(context, StoreNotifyPrefs.Kind.UPDATE_AVAILABLE, StoreNotifyPrefs.Channel.PUSH)) return@runCatching false
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // Silent in Cloud Store (a new "_silent_v2" channel, LOW: no sound, no pop-up), unchanged elsewhere.
            val channel = SilentChannels.ensure(context, NOTIF_CHANNEL, "Updater", NotificationManager.IMPORTANCE_HIGH)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
            // #1551 one notification AND one PendingIntent per package: under the one shared id each new prompt replaced the
            // previous app's, so every app but the last waited on a tap nobody could make.
            val pi = PendingIntent.getActivity(context, confirmId(pkg), confirm, flags)
            val notif = NotificationCompat.Builder(context, channel)
                .apply { SilentChannels.quiet(context, this) }
                .setContentTitle("$subject — tap to finish installing")
                .setContentText("Android needs one tap from you to confirm this install.")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(confirmId(pkg), notif)
            nm.areNotificationsEnabled() &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    nm.getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE)
        }.getOrDefault(false)

    /** #894 True the first time this exact failure (package, build, reason) is seen. */
    private fun isNew(context: Context, pkg: String, build: String, reason: String): Boolean =
        pkg.isEmpty() || PassLedgerStore.firstTimeFailure(context, pkg, build, reason)

    /** [quiet]: already filed under the pass summary, or the same failure as last time - say nothing.
     *  [raise]=false: in-app feed and toast only, no fleet alert (successes). */
    private fun surface(context: Context, short: String, full: String,
                        severity: String = NotificationStore.Sev.INFO, key: String = short,
                        quiet: Boolean = false, raise: Boolean = true) {
        if (quiet) return
        // #894 Store / update messages are Cloud Store's: this package posts none (no feed entry,
        // no toast, no alert).
        if (!StoreNotifyGate.mayPost(context)) return
        // Configs ▸ Notifications: a failure is "Failures", any other result "Install done"; each on its own two channels.
        val kind = if (severity == NotificationStore.Sev.ERROR) StoreNotifyPrefs.Kind.FAILURES else StoreNotifyPrefs.Kind.INSTALL_DONE
        val toFeed = StoreNotifyPrefs.allows(context, kind, StoreNotifyPrefs.Channel.IN_APP)
        val toPush = StoreNotifyPrefs.allows(context, kind, StoreNotifyPrefs.Channel.PUSH)
        // Mirror into the in-app feed so the launcher badge AND the
        // Cloud-SuperApp Notifications panel reflect the same event.
        // Without this push the framework notification (and its badge)
        // shows up but the in-app list stays empty — exactly the bug
        // the user reported.
        if (toFeed) runCatching {
            NotificationStore.push(
                ctx      = context,
                source   = "Updater",
                title    = short,
                body     = full,
                severity = severity,
            )
        }
        // AN UNATTENDED PASS MAY NOT INTERRUPT.
        // Nobody asked for this work, so a toast per install result — thirty-
        // two of them over a fleet pass, success included — is precisely the
        // "progress" that auto-update:ON is meant to mean the absence of. The
        // in-app feed above still records every one; only the interruption is
        // dropped, and only for routine outcomes. A FAILURE still surfaces:
        // silence about work that did not happen is not quiet, it is hiding.
        // Manual installs are unaffected — the flag is set only around the
        // automatic pass — so tapping Install still confirms itself.
        val unattended = runCatching { AutoUpdatePrefs.unattendedPass(context) }.getOrDefault(false)
        val routine = severity != NotificationStore.Sev.ERROR
        if (unattended && routine) return
        if (!toPush) return
        try {
            Toast.makeText(context, short, Toast.LENGTH_LONG).show()
        } catch (_: Throwable) { /* off-Looper thread — skip toast */ }

        if (!raise) return
        // #777: the result is a fleet ALERT — it lands in the SuperApp's Alerts
        // group (or this app's own notification if there is no SuperApp), one
        // per app: a retry's result replaces the failure it answers.
        FleetAlerts.raise(context, FleetAlerts.Alert(
            title = short,
            text = full,
            severity = severity,
            deepLink = UpdaterHost.alertLink,
            dedupeKey = "updater:$key",
        ))
    }

    companion object {
        /** #906 what the Store row quotes while Android's install prompt is unanswered. */
        const val PENDING_STATUS_TEXT = "PENDING_USER_ACTION (Android is waiting for your tap on the install prompt)"
        /** The "tap to finish installing" channel and its notification ids (confirm = id + 1);
         *  public so the SuperApp can retire what an older build posted (#894). */
        const val UPDATER_CHANNEL = "superapp-updater"
        const val UPDATER_NOTIF_ID = 0xC10D

        /** #1551 The "tap to finish installing" notification id of [pkg]: one per package, never the progress id. */
        fun confirmId(pkg: String): Int = UPDATER_NOTIF_ID + 2 + (pkg.hashCode() and 0xFFFF)

        /** Set on the PendingIntent so this receiver can tell an uninstall
         *  (long-press menu) from an install/update and message accordingly. */
        const val EXTRA_OP = "com.diegonmarcos.superapp.updater.OP"
        const val OP_UNINSTALL = "uninstall"

        /** Absolute path of the cached APK, deleted on confirmed success. */
        const val EXTRA_APK_PATH = "apk_path"

        /** Package this session installs, used as the InstallGate key. */
        const val EXTRA_TARGET_PKG = "target_pkg"

        /** EXTRA_STATUS absent. Not a value Android defines — ours, so that
         *  "the installer answered without a status" stays distinguishable
         *  from the statuses it does define instead of being read as one. */
        private const val NO_STATUS = -999
    }
}

package com.diegonmarcos.superapp.updater.install

import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.PackageInstallerReceiver
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.ensureShellChannel
import com.diegonmarcos.superapp.updater.shellChannelDiagnosis
import com.diegonmarcos.superapp.updater.apk.VerifiedApk
import com.diegonmarcos.superapp.updater.source.ApkSource
import android.content.Context
import android.util.Log

/**
 * One way to get a verified APK onto the device.
 *
 * The mirror image of [ApkSource]: sources answer "where does the APK come
 * from", channels answer "how does it get installed". Both are ordered lists
 * tried in turn, and both use the same "return null/false = not my job, try
 * the next one" contract — so adding a channel (a future device-owner or
 * privileged-manager install) is adding one object to Fleet.channels.
 */
internal interface InstallChannel {
    val name: String
    /**
     * Null if this channel took the install (committed or handed off to a
     * system UI that will). Otherwise a sentence saying WHY it declined, and
     * the caller tries the next.
     *
     * This used to return a bare false, which is how "no install channel
     * accepted <pkg>" came to be the entire story a user got: never connected,
     * pairing lost, `pm install` refused, a truncated staging copy — every
     * distinct cause collapsed into the same one bit.
     */
    fun install(ctx: Context, app: Fleet.App, apk: VerifiedApk): String?
}

/** Shell install (Shizuku / embedded adb) — no dialog at all. */
internal object ShellInstall : InstallChannel {
    override val name = SilentReadiness.CHANNEL_SHELL
    private const val TAG = "FleetUpdater"

    override fun install(ctx: Context, app: Fleet.App, apk: VerifiedApk): String? {
        // The blocking `pm install` call below can take up to ~25s. Without
        // this, the overlay is left showing the last Downloading frame (or an
        // even staler one) for the whole install, which reads as frozen —
        // exactly the "batch progress looks stuck" bug this exists to end.
        UpdateProgress.update(UpdateProgress.State.Installing)
        val declined = shellInstall(ctx, apk)
        if (declined != null) return declined
        UpdateProgress.update(UpdateProgress.State.Done)
        return null
    }

    /** Zero-dialog install. The shell channel (Shizuku / embedded adb) runs as
     *  uid 2000, which holds INSTALL_PACKAGES — so `pm install` neither shows the
     *  "are you sure you want to update this app" confirm nor gives Play Protect
     *  a chance to stack its scan prompt on top. This is the same channel the
     *  Play-Protect toggle already uses (adbdebug.PackageVerifier).
     *
     *  THE APK IS STREAMED, NEVER HANDED OVER AS A PATH.
     *
     *  This used to copy the APK into getExternalFilesDir() and run
     *  `pm install <path>`. uid 2000 cannot read that path: /Android/data is
     *  FUSE-restricted from Android 11 — and `pm` reports the failure on
     *  STDERR, which the adb `exec:` service does not carry. The observed
     *  result was the worst possible one: ZERO bytes of output and a hang
     *  until the exec timeout, once per app, after which every install fell
     *  through to the prompting fallback and the user saw a dialog with
     *  Wireless debugging switched on. Hence `pm install -S <size>`, which
     *  reads the package from stdin exactly as `adb install` does: nothing is
     *  shared with the shell through the filesystem, and nothing is left
     *  behind on shared storage. (/data/local/tmp is not an option either —
     *  0771 root:shell, so an untrusted_app cannot create anything in it.)
     *
     *  Any failure at all returns the reason, and the caller reports it — or,
     *  where the prompting fallback is still enabled, falls through to the
     *  PackageInstaller path.
     *
     *  Blocking (the transfer plus the commit, plus up to ~10s of mDNS if a
     *  reconnect is needed); callers are already off the main thread.
     *
     *  Not private: [com.diegonmarcos.superapp.updater.UpdateWorker]'s
     *  self-update takes this same rung first (#588). */
    fun shellInstall(ctx: Context, apk: VerifiedApk): String? {
        // ESTABLISH, don't merely probe. activeShellChannel() only reported
        // whether a channel happened to be live, so an unattended install found
        // "none" on a paired phone whose embedded adb client had simply not
        // reconnected since the last app start — the connection is in-process
        // state, so it does not survive one. ensureShellChannel() runs the same
        // no-user-step mDNS reconnect the boot path does, then re-probes. It
        // comes from src/shell or src/noshell depending on whether this app
        // declares :libs:shizuku-adb-debug-tools; the stub always returns null.
        val channel = ensureShellChannel(ctx)
            ?: return "no privileged shell channel could be established, even after a " +
                      "reconnect attempt (${shellChannelDiagnosis(ctx)})"
        // PROVE THE CHANNEL EXECUTES BEFORE SENDING IT TENS OF MEGABYTES.
        // isReady()/ensureShellChannel() only establish that a socket is up. A
        // channel that is connected but wedged answers nothing, and finding
        // that out via the install itself means paying the full timeout per
        // app — a whole fleet pass of dead wall clock. A trivial round trip
        // costs milliseconds, and fails in seconds rather than tens of them.
        if (!channel.probe(ctx)) {
            return "${channel.name()} is connected but did not answer a trivial `echo` " +
                   "round trip — the channel is up but not usable"
        }
        /**
         * A channel that cannot stream stdin (the uid-2000 local server) still EXECUTES, so the
         * package goes by path instead: this app writes it to its external files dir, which the
         * shell user can read, and the shell copies it to /data/local/tmp, which system_server can
         * read (it cannot read the FUSE-backed external dir: "no access to read file context
         * fuse"). Returns the /data/local/tmp path, or null with nothing left behind.
         */
        fun stageForShell(f: java.io.File, tag: String): String? {
            val dir = ctx.getExternalFilesDir("shell-stage") ?: return null
            val copy = java.io.File(dir, "$tag.apk")
            return try {
                f.copyTo(copy, overwrite = true)
                val target = "/data/local/tmp/cloud-${ctx.packageName}-$tag.apk"
                val out = channel.exec(ctx, "cp '${copy.absolutePath}' '$target' && chmod 644 '$target' && echo staged 2>&1")
                if (out?.contains("staged") == true) target else null
            } catch (t: Throwable) {
                Log.w(TAG, "staging $tag for the shell failed", t); null
            } finally { copy.delete() }
        }
        fun unstage(target: String) { runCatching { channel.exec(ctx, "rm -f '$target'") } }

        /**
         * A base APK plus its splits (Google Play's delivery) in ONE `pm` session:
         * install-create, one install-write per file streamed over stdin exactly as
         * the single-APK path streams, then install-commit. A split app installed
         * one file at a time does not install at all, so a failure at any step
         * abandons the session and returns the step's own words.
         */
        fun sessionInstall(): String? {
            val files = apk.allFiles
            val total = files.sumOf { it.length() }
            val created = channel.exec(ctx, "pm install-create -r -d -S $total 2>&1")?.trim().orEmpty()
            val sid = Regex("\\[(\\d+)]").find(created)?.groupValues?.get(1)
                ?: return "${channel.name()}: `pm install-create` answered: ${created.ifBlank { "nothing" }}"
            fun abandon(why: String): String {
                runCatching { channel.exec(ctx, "pm install-abandon $sid 2>&1") }
                return why
            }
            return try {
                files.forEachIndexed { i, f ->
                    val name = if (i == 0) "base.apk" else "split$i-${f.name.substringAfterLast('-')}"
                    val out = channel.execWithStdin(ctx, "pm install-write -S ${f.length()} $sid $name - 2>&1", f)?.trim()
                        ?: stageForShell(f, "$sid-$i")?.let { t ->
                            try { channel.exec(ctx, "pm install-write -S ${f.length()} $sid $name '$t' 2>&1")?.trim() }
                            finally { unstage(t) }
                        }
                        ?: return abandon("${channel.name()} can neither stream a split over stdin nor read a staged copy")
                    if (!out.startsWith("Success"))
                        return abandon("${channel.name()}: `pm install-write` of ${f.name} answered: ${out.ifBlank { "nothing" }}")
                }
                val done = channel.exec(ctx, "pm install-commit $sid 2>&1")?.trim().orEmpty()
                Log.i(TAG, "shell split install via ${channel.name()} (${files.size} files): ${done.ifBlank { "no output" }}")
                if (done.startsWith("Success")) null
                else abandon("${channel.name()}: `pm install-commit` of ${files.size} files answered: ${done.ifBlank { "nothing" }}")
            } catch (t: Throwable) {
                Log.w(TAG, "shell split install threw", t)
                abandon("${channel.name()} threw ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        if (apk.splits.isNotEmpty()) return sessionInstall()
        val src = apk.file
        return try {
            // -r reinstall, -d allow version downgrade, -S <size> read the
            // package from stdin. Deliberately NOT -g: on a reinstall the
            // runtime grants already carry over, and -g fails the whole install
            // on any permission the platform won't auto-grant.
            // 2>&1 because `exec:` carries stdout only — without it every `pm`
            // diagnostic is discarded and any failure reads as "no output",
            // which is precisely how the old staged-path bug hid for so long.
            val out = channel.execWithStdin(
                ctx, "pm install -r -d -S ${src.length()} 2>&1", src,
            )?.trim()
                ?: stageForShell(src, "single-${src.nameWithoutExtension}")?.let { t ->
                    try { channel.exec(ctx, "pm install -r -d '$t' 2>&1")?.trim() } finally { unstage(t) }
                }
                ?: return "${channel.name()} cannot stream a package over stdin, and staging it " +
                          "through this app's external files dir to /data/local/tmp failed"
            Log.i(TAG, "shell install via ${channel.name()}: ${out.ifBlank { "no output" }}")
            if (out.startsWith("Success")) null
            else "${channel.name()} streamed ${src.length()} bytes to `pm install -S` and it " +
                 "answered: " + out.ifBlank { "nothing at all — the channel dropped mid-command" }
        } catch (t: Throwable) {
            Log.w(TAG, "shell install threw", t)
            "${channel.name()} threw ${t.javaClass.simpleName}: ${t.message}"
        }
    }


}

/** PackageInstaller session — always available, but prompts the user. The
 *  result arrives asynchronously at [PackageInstallerReceiver], which is what
 *  moves UpdateProgress off Installing; hence no Done here. */
internal object SessionInstall : InstallChannel {
    override val name = SilentReadiness.CHANNEL_SESSION
    override fun install(ctx: Context, app: Fleet.App, apk: VerifiedApk): String? {
        // #1675 handed to Cloud Store (the installer of record): it was taken, by Cloud Store, so this channel took it.
        try { UpdateInstaller(ctx).install(apk, app.pkg) } catch (h: HandedToStore) {
            UpdateProgress.update(UpdateProgress.State.Waiting(h.message ?: "Cloud Store installs ${app.label}"))
        }
        return null
    }
}

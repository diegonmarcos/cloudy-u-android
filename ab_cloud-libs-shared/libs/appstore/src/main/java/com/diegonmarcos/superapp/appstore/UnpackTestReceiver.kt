package com.diegonmarcos.superapp.appstore

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * #1121 Test entry: makes the Store (a package of its own) start a terminal's unpack exactly as it does
 * after an install, so an emulator test can prove the cross-process hand-off (the Store's ResultReceiver
 * has to unmarshal in the TERMINAL's classloader). adb shell holds DUMP, no other app does, so only
 * `adb shell am broadcast -n <store>/...UnpackTestReceiver -a [ACTION] --es pkg <terminal>` reaches it.
 */
class UnpackTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_SESSIONS) { SessionStress.start(context, goAsync()); return }
        if (intent.action != ACTION) return
        val pkg = intent.getStringExtra("pkg") ?: return
        // Keep the process (and so the reply binder) alive until the terminal has answered.
        val pending = goAsync()
        UnpackAfterInstall.run(context, pkg)
        Handler(Looper.getMainLooper()).postDelayed({ pending.finish() }, HOLD_MS)
    }

    companion object {
        const val ACTION = "com.diegonmarcos.cloud.action.TEST_UNPACK"
        /** #1735 emulator test: 40 real PackageInstaller sessions through [InstallSessions], result in files/session-stress-result.json. */
        const val ACTION_SESSIONS = "com.diegonmarcos.cloud.action.TEST_SESSIONS"
        private const val HOLD_MS = 45_000L   // under the 60 s broadcast limit: a longer goAsync hold is an ANR of the Store
    }
}

/**
 * #1735 The real-PackageInstaller half of the session test. Pre-leaks [LEAKED] uncommitted sessions (what the old installer
 * left behind), then runs [INSTALLS] installs through the production [InstallSessions] policy: every fourth dies before commit,
 * the rest commit a fixture that is not an APK (Android answers each with a failure and closes the session, so the test never
 * replaces the Store under itself). Passes when every install returned, none hit the cap error out loud, and zero sessions
 * stay open once the system has answered the committed ones.
 */
internal object SessionStress {
    const val INSTALLS = 40
    const val LEAKED = 45

    fun start(ctx: Context, pending: BroadcastReceiver.PendingResult) {
        Thread {
            val out = java.io.File(ctx.filesDir, "session-stress-result.json")
            out.delete()
            val r = runCatching { run(ctx) }.getOrElse { """{"ok":false,"error":"${it.javaClass.simpleName}: ${it.message?.replace('"', '\'')}"}""" }
            out.writeText(r)
            pending.finish()
        }.start()
    }

    private fun run(ctx: Context): String {
        val installer = ctx.packageManager.packageInstaller
        fun params() = android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        repeat(LEAKED) { runCatching { installer.createSession(params()) } }
        val before = com.diegonmarcos.superapp.updater.install.InstallSessions.openCount(ctx)
        val port = com.diegonmarcos.superapp.updater.install.InstallSessions.AndroidPort(installer, params())
        var peak = 0; var failures = 0; var committed = 0; var capErrors = 0
        val cb = android.app.PendingIntent.getBroadcast(ctx, 0,
            Intent(ctx, UnpackTestReceiver::class.java).setPackage(ctx.packageName).setAction("com.diegonmarcos.cloud.action.TEST_SESSION_STATUS"),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE)
        for (i in 0 until INSTALLS) {
            try {
                val id = com.diegonmarcos.superapp.updater.install.InstallSessions.open(port,
                    onSuperseded = { com.diegonmarcos.superapp.updater.install.SupersededSessions.add(ctx, it) })
                peak = maxOf(peak, com.diegonmarcos.superapp.updater.install.InstallSessions.openCount(ctx))
                com.diegonmarcos.superapp.updater.install.InstallSessions.guarded<Unit>(port, id) {
                    if (i % 4 == 0) error("simulated failure before commit")
                    installer.openSession(id).use { s ->
                        s.openWrite("base.apk", 0, 16).use { o -> o.write(ByteArray(16)); s.fsync(o) }
                        s.commit(cb.intentSender)
                    }
                    committed++
                    true to Unit
                }
            } catch (t: Throwable) {
                if (com.diegonmarcos.superapp.updater.install.InstallSessions.isCapError(t)) capErrors++
                if (i % 4 != 0) failures++
            }
        }
        // The system answers the committed ones; wait for the table to drain.
        var left = com.diegonmarcos.superapp.updater.install.InstallSessions.openCount(ctx)
        val until = System.currentTimeMillis() + 90_000
        while (left != 0 && System.currentTimeMillis() < until) {
            Thread.sleep(1_000)
            left = com.diegonmarcos.superapp.updater.install.InstallSessions.openCount(ctx)
            // Anything still here after the answers is ours to reap on demand (diagnostics button path).
            if (left != 0 && System.currentTimeMillis() > until - 60_000)
                com.diegonmarcos.superapp.updater.install.InstallSessions.reapOwn(ctx, aggressive = true)
        }
        val ok = capErrors == 0 && failures == 0 && left == 0
        return """{"ok":$ok,"installs":$INSTALLS,"leaked_before":$LEAKED,"open_before":$before,"peak":$peak,"committed":$committed,"cap_errors":$capErrors,"unexpected_failures":$failures,"open_after":$left}"""
    }
}

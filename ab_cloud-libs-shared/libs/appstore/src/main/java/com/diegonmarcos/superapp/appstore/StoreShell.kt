package com.diegonmarcos.superapp.appstore

import android.content.Context
import com.diegonmarcos.superapp.shellclient.ShellClient
import com.diegonmarcos.superapp.shellclient.ShellResult
import com.diegonmarcos.superapp.shellclient.ShellStatus

/**
 * #1512 The Store's one door to a privileged shell: the SuperApp's shell broker (libs:shell-client). No channel of its own here:
 * no pairing, no embedded adb, no service. Every command runs under the CALLING app's allowlist entry
 * (assets/shell-allowlist.json of libs:shell-broker); a refusal comes back as text, an absent or unarmed broker as null.
 */
internal object StoreShell {
    private const val TIMEOUT_MS = 30_000L

    /** The command's output (stderr merged by the one redirect the broker allows), the broker's refusal as a line, or null = no armed broker. Blocking. */
    fun exec(ctx: Context, cmd: String): String? = text(ShellClient.exec(ctx, if (cmd.endsWith(MERGE)) cmd else cmd + MERGE, TIMEOUT_MS))

    /** Force-stop of <pkg>: "OK" when it exited 0, else what it said; null when no broker is armed. */
    fun forceStop(ctx: Context, pkg: String): String? = when (val r = ShellClient.exec(ctx, "am force-stop $pkg $ERR", TIMEOUT_MS)) {
        is ShellResult.Ok -> if (r.exitCode == 0) "OK" else (r.stdout + r.stderr).ifBlank { "exit ${r.exitCode}" }
        else -> text(r)
    }

    fun status(ctx: Context): ShellStatus = ShellClient.status(ctx)

    /** #1682 THE Store's ADB door: the ADB owner's (Cloud C3 Webserver) page, by the client-safe explicit intent (ShellWire.BROKER_PKG); the Store links no owner lib, so this is what AdbShellLink.open resolves to for a client. */
    fun arm(ctx: Context): Boolean {
        if (ShellClient.superAppInstalled(ctx)) return ShellClient.openArmPage(ctx)
        // Cloud C3 Webserver is missing: offer the install (the Store's own hand-off queues it on the Cloud tab) instead of a dead tap.
        return offerInstall(ctx)
    }

    /** Opens the Store's Cloud tab with the ADB owner queued for install, and says so. False (with a toast) when that cannot start. Never throws. */
    fun offerInstall(ctx: Context): Boolean {
        val started = runCatching {
            ctx.startActivity(com.diegonmarcos.superapp.updater.install.StoreHandoff.intent(ctx.packageName, com.diegonmarcos.superapp.shellclient.ShellWire.BROKER_PKG)); true
        }.getOrDefault(false)
        runCatching { android.widget.Toast.makeText(ctx, if (started) "Cloud C3 Webserver is not installed: installing it from the Store (it owns the ADB shell)."
            else "Cloud C3 Webserver is not installed: it owns the ADB shell. Install it from the Store Cloud tab.", android.widget.Toast.LENGTH_LONG).show() }
        return started
    }

    internal fun text(r: ShellResult): String? = when (r) {
        is ShellResult.Ok -> r.stdout + r.stderr
        is ShellResult.Denied -> "denied by the shell broker: ${r.reason}"
        else -> null
    }

    private const val ERR = "2>&1"
    private const val MERGE = " $ERR"
}

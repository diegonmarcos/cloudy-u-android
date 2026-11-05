package com.diegonmarcos.superapp.profile

import android.content.Context
import com.diegonmarcos.superapp.shellclient.ShellClient
import com.diegonmarcos.superapp.shellclient.ShellResult

/**
 * #1512 The account lib's one door to a privileged shell: the SuperApp's shell broker (libs:shell-client). It owns no channel: no
 * pairing, no embedded adb, no service, no boot hook. Commands run under the CALLING app's allowlist entry
 * (libs:shell-broker assets/shell-allowlist.json); the shape (`name()`, `exec(ctx, command)`) is what the runbook and the perms plan
 * were written against, so they read the same.
 */
interface ShellChannel {
    fun name(): String
    /** The command's output (stderr merged by the one redirect the broker allows), the broker's refusal as a line, or null = no answer. Blocking. */
    fun exec(ctx: Context, command: String): String?
}

object ShellChannels {
    private const val TIMEOUT_MS = 30_000L

    private object Broker : ShellChannel {
        override fun name(): String = "shell-broker"
        override fun exec(ctx: Context, command: String): String? =
            when (val r = ShellClient.exec(ctx, if (command.endsWith(" 2>&1")) command else "$command 2>&1", TIMEOUT_MS)) {
                is ShellResult.Ok -> r.stdout + r.stderr
                is ShellResult.Denied -> "denied by the shell broker: ${r.reason}"
                else -> null
            }
    }

    /** The broker as a channel while it is reachable AND armed; null otherwise. A provider call: off the main thread. */
    fun active(ctx: Context): ShellChannel? = if (ShellClient.status(ctx).armed) Broker else null

    /** One human line: what the broker says about itself. */
    fun status(ctx: Context): String = ShellClient.status(ctx).detail
}

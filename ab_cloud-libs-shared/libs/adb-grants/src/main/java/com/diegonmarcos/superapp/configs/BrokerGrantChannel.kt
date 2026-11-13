package com.diegonmarcos.superapp.configs

import android.content.Context
import com.diegonmarcos.superapp.perms.PermsGrant
import com.diegonmarcos.superapp.shellclient.ShellClient
import com.diegonmarcos.superapp.shellclient.ShellResult

// #1682 P3: moved from the SuperApp's configs/PermsCenter.kt; P3 step 5: now client-safe (libs:adb-grants), it runs over the
// ShellClient wire, so the owner's own process (through its own provider) and a client-only app share it. Package kept.

/**
 * #1510 / #1519 the shell broker as the page's grant channel: `up` = a privileged channel is armed; `exec` answers the
 * command's output, an `error:` line when the broker refused it or it exited non-zero, null when nothing is armed.
 */
class BrokerGrantChannel(private val ctx: Context) : PermsGrant.GrantChannel {
    override fun up() = com.diegonmarcos.superapp.system.AdbReadiness.check(ctx).usable
    override fun exec(command: String): String? =
        when (val r = ShellClient.exec(ctx, command)) {
            is ShellResult.Ok ->
                (r.stdout + r.stderr).trim().ifEmpty { if (r.exitCode == 0) "" else "error: exit ${r.exitCode}" }
            is ShellResult.Denied -> "error: refused by the shell broker (${r.reason})"
            is ShellResult.Failed -> "error: ${r.reason}"
            else -> null
        }
}

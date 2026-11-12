package com.diegonmarcos.superapp.configs

import android.content.Context
import com.diegonmarcos.superapp.perms.PermsGrant

// #1682 P3: moved verbatim from the SuperApp's configs/PermsCenter.kt into the owner-side lib (it runs on the owner's broker in
// process); the SuperApp's Perms page and the owner's ADB page share it. Package kept: no call site changes.

/**
 * #1510 / #1519 the shell broker as the page's grant channel: `up` = a privileged channel is armed; `exec` answers the
 * command's output, an `error:` line when the broker refused it or it exited non-zero, null when nothing is armed.
 */
class BrokerGrantChannel(private val ctx: Context) : PermsGrant.GrantChannel {
    override fun up() = com.diegonmarcos.superapp.system.AdbReadiness.check(ctx).usable
    override fun exec(command: String): String? =
        when (val r = com.diegonmarcos.superapp.shellbroker.ShellBroker.exec(ctx, com.diegonmarcos.superapp.shellbroker.ShellBroker.SELF, command)) {
            is com.diegonmarcos.superapp.shellclient.ShellResult.Ok ->
                (r.stdout + r.stderr).trim().ifEmpty { if (r.exitCode == 0) "" else "error: exit ${r.exitCode}" }
            is com.diegonmarcos.superapp.shellclient.ShellResult.Denied -> "error: refused by the shell broker (${r.reason})"
            is com.diegonmarcos.superapp.shellclient.ShellResult.Failed -> "error: ${r.reason}"
            else -> null
        }
}


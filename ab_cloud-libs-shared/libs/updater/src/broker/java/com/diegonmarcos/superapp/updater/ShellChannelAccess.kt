package com.diegonmarcos.superapp.updater

import android.content.Context
import com.diegonmarcos.superapp.shellclient.ShellClient
import com.diegonmarcos.superapp.shellclient.ShellResult
import java.io.File

/**
 * #1512 The BROKER variant: compiled into every app that links :libs:shell-client and NOT :libs:shizuku-adb-debug-tools
 * (Cloud Store, Cloud Account, Cloud Drive). Silent installs and the self-update go through the SuperApp's shell broker: the
 * APK is streamed over its stdin door (`pm install -S <size>`, `pm install-write -S <size> ... -`), allowlisted per caller.
 * No channel of its own here: no pairing, no reconnect, no service. The members mirror [ShellChannelAccess]'s other twins
 * (src/shell, src/noshell); the SuperApp build compiles the real interface.
 */
internal interface ShellChannel {
    fun exec(ctx: Context, command: String): String?
    fun probe(ctx: Context): Boolean = false
    fun execWithStdin(ctx: Context, command: String, stdin: File): String? = null
    fun name(): String
}

/** The text a `pm` verb is judged by: stdout (stderr is merged by the ` 2>&1` the callers spell), or the refusal. Null = no answer. */
internal fun brokerText(r: ShellResult): String? = when (r) {
    is ShellResult.Ok -> r.stdout + r.stderr
    is ShellResult.Denied -> "denied by the shell broker: ${r.reason}"
    else -> null
}

internal class BrokerShellChannel(private val client: ShellClient) : ShellChannel {
    override fun name(): String = "shell-broker"
    override fun exec(ctx: Context, command: String): String? = brokerText(client.exec(command.trim(), EXEC_TIMEOUT_MS))
    override fun probe(ctx: Context): Boolean =
        (client.exec("echo shell-ok", PROBE_TIMEOUT_MS) as? ShellResult.Ok)?.stdout?.contains("shell-ok") == true
    override fun execWithStdin(ctx: Context, command: String, stdin: File): String? =
        brokerText(client.execStdin(command.trim(), stdin))

    private companion object {
        const val EXEC_TIMEOUT_MS = 60_000L
        const val PROBE_TIMEOUT_MS = 20_000L   // the broker may need one arm attempt on the first call
    }
}

/** A channel only while the broker is reachable AND armed (a probe: no arm attempt, no UI). */
internal fun activeShellChannel(ctx: Context): ShellChannel? =
    if (ShellClient.status(ctx).armed) BrokerShellChannel(ShellClient.over(ctx)) else null

/** The broker arms itself on the first exec (its own switch and back-off decide), so "established" = the broker is installed. */
internal fun ensureShellChannel(ctx: Context): ShellChannel? =
    if (ShellClient.status(ctx).brokerInstalled) BrokerShellChannel(ShellClient.over(ctx)) else null

internal fun shellChannelDiagnosis(ctx: Context): String {
    val s = ShellClient.status(ctx)
    return "shell-broker — ${s.detail}"
}

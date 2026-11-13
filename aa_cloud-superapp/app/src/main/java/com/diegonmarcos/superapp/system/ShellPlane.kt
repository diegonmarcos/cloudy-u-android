package com.diegonmarcos.superapp.system

import android.content.Context
import com.diegonmarcos.superapp.shellclient.ShellClient
import com.diegonmarcos.superapp.shellclient.ShellResult

/**
 * #1682 P3: "the shell this screen grants through", as the Permissions page reads it: the owner's broker (libs:shell-client) as the
 * readiness state last saw it. [current] is a cached read (never a round trip on the main thread); [exec] is one allowlisted broker call.
 */
class ShellPlane(private val via: String) {
    fun name(): String = via

    /** Blocking (the broker call). stdout + stderr, or null when the broker refused / did not answer. */
    fun exec(ctx: Context, cmd: String): String? =
        (ShellClient.exec(ctx.applicationContext, cmd) as? ShellResult.Ok)?.let { it.stdout + it.stderr }

    companion object {
        fun current(@Suppress("UNUSED_PARAMETER") ctx: Context): ShellPlane? = AdbReadiness.state.value.takeIf { it.usable }?.let { ShellPlane(it.via) }
    }
}

package com.diegonmarcos.superapp.perms

import android.content.Context

/**
 * #1513 the ONE grant row type for every permission only the shell can give (catalogue kind `adb_only`, e.g. WRITE_SECURE_SETTINGS):
 * the Perms row offers Grant; with a channel armed the app grants itself and reads the result back, without one the row copies the
 * `adb shell` line and opens the pairing flow. Pure rules here (JVM-tested); the row and the keeper's automatic request use them,
 * no screen has grant logic of its own.
 */
object AdbGrant {
    /** The log's "who re-armed" value for a re-arm that followed an automatic self-grant. */
    const val BY_SELF = "superapp (self-grant)"

    fun command(pkg: String, perm: String) = PermsShell.grant(pkg, perm)
    fun adbLine(pkg: String, perm: String) = "adb shell ${command(pkg, perm)}"

    enum class Offer {
        /** Held: nothing to ask. */
        NONE,
        /** A channel is armed: Grant grants now. */
        GRANT,
        /** No channel: copy the command and pair this app. */
        COPY,
    }

    fun offer(held: Boolean, channelUp: Boolean): Offer = when {
        held -> Offer.NONE
        channelUp -> Offer.GRANT
        else -> Offer.COPY
    }

    /** Automatic request: wanted by its feature, permission missing, channel armed, and not tried before (the flag resets once held). */
    fun shouldAutoRequest(wanted: Boolean, held: Boolean, channelUp: Boolean, alreadyTried: Boolean): Boolean =
        wanted && !held && channelUp && !alreadyTried

    /** Runs the grant through [run], then reads the permission back with [held]; true only when it is held after. */
    fun grant(pkg: String, perm: String, run: (String) -> Unit, held: () -> Boolean): Boolean {
        if (held()) return true
        run(command(pkg, perm))
        return held()
    }

    fun held(ctx: Context, perm: String): Boolean =
        ctx.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** A feature that waits on a grant (the wireless-debugging keeper) listens here and acts at once; called after a grant and on resume while held. */
    @Volatile var onHeld: ((Context, String) -> Unit)? = null
}

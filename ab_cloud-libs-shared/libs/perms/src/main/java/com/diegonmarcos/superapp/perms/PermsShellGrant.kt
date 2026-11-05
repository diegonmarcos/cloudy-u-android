package com.diegonmarcos.superapp.perms

/**
 * #1519 "Grant via shell" of one row: runs the row's commands (`pm grant <pkg> <perm>`, `appops set`, `cmd ...`) on the host's
 * shell channel (the SuperApp's shell broker) and reads the result back; the row is granted only when the read-back says so.
 * Pure over [PermsGrant.GrantChannel] (PermsShellGrantTest).
 */
object PermsShellGrant {
    data class Outcome(val ok: Boolean, val message: String)

    /** The commands that grant [e] on [pkg], or null when no shell line can (Health Connect, USB, listener without a component...). */
    fun commands(pkg: String, e: PermEntry, s: PermStatus = PermStatus(PermState.DENIED)): List<String>? =
        (PermsActions.commands(pkg, PermsView.item(e, s)) as? PermsActions.Grant.Shell)?.commands

    fun run(title: String, commands: List<String>, channel: PermsGrant.GrantChannel, held: () -> Boolean): Outcome {
        if (held()) return Outcome(true, "$title: already granted")
        if (!channel.up()) return Outcome(false, "$title: ${PermsGrant.DISABLED_REASON}")
        for (c in commands) {
            val why = PermsGrant.failure(channel.exec(c))
            if (why != null) return Outcome(false, "$title: the shell refused it ($why)")
        }
        return if (held()) Outcome(true, "$title: granted by shell") else Outcome(false, "$title: the shell ran it but it is still not granted")
    }
}

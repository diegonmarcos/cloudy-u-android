package com.diegonmarcos.superapp.shellclient

/**
 * #1510 The answer to one shell call. The broker never throws across the wire: every outcome is one of these four.
 *  - [Ok]        the command ran; [Ok.exitCode] is the shell's, [Ok.source] names the channel that served it.
 *  - [NeedsArm]  the broker is reachable but no privileged channel is armed: the UI offers "Arm in SuperApp".
 *  - [Denied]    the caller has no allowlist entry for this command (default deny), or the command is malformed.
 *  - [Failed]    the broker could not serve it (SuperApp absent, busy, timed out, wire error).
 */
sealed class ShellResult {
    data class Ok(val exitCode: Int, val stdout: String, val stderr: String, val source: String) : ShellResult() {
        val success: Boolean get() = exitCode == 0
    }
    object NeedsArm : ShellResult() { override fun toString() = "NeedsArm" }
    data class Denied(val reason: String) : ShellResult()
    data class Failed(val reason: String, val timedOut: Boolean = false) : ShellResult()

    /** stdout when the command ran and exited 0, else null: the shape most callers of the old channel expected. */
    fun stdoutOrNull(): String? = (this as? Ok)?.takeIf { it.success }?.stdout
}

/**
 * [armed] = a privileged channel answered a live round trip (`id -u` == 2000) at [checkedAt]; never a flag, never a last-known value.
 * [channel] its name; [detail] one human line; [reason] why it is down ("" when armed); [checkedAt] epoch ms of the check (0 = never checked).
 */
data class ShellStatus(val brokerInstalled: Boolean, val armed: Boolean, val channel: String, val detail: String,
                       val checkedAt: Long = 0L, val reason: String = "") {
    /** The one line every ADB/shell indicator shows: green only when [armed]; else the real reason; always the check time. */
    fun line(): String {
        val at = if (checkedAt > 0) " (checked ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(checkedAt))})" else ""
        return if (armed) "ADB shell: up via $channel$at" else "ADB shell: down - ${reason.ifBlank { detail }.ifBlank { "unknown" }}$at"
    }
}

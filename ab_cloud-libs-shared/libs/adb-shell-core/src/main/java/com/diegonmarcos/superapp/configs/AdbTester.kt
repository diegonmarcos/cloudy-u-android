package com.diegonmarcos.superapp.configs

import android.content.Context
import com.diegonmarcos.superapp.shellbroker.ShellBroker
import com.diegonmarcos.superapp.shellclient.ShellResult
import com.diegonmarcos.superapp.system.AdbReadiness

/**
 * #1678 The ADB Shell > Status "Tester": real, harmless commands through the SAME path the status dropdown, X-Ray and every SuperApp
 * feature use (the broker: [ShellBroker.status] for the live round trip, [ShellBroker.exec] under the SuperApp's own allowlist entry).
 * Per command: the command, the channel that answered, exit code, round-trip time, first lines of output or the exact error.
 * Runs only when tapped; never throws.
 */
/** #1682 P3 step 4a: owner-side lib (libs:adb-shell-core), public so the SuperApp host and the webserver owner side share it. */
object AdbTester {

    data class Line(val cmd: String, val ok: Boolean, val channel: String, val exit: Int?, val ms: Long, val output: String)

    /** The commands, in order: the readiness round trip, a property read, a broker dumpsys read, a dropdown probe verbatim. */
    val COMMANDS = listOf("getprop ro.build.version.release", "dumpsys battery", "dumpsys thermalservice | head -300")

    const val STATUS_CMD = "broker live check (id -u)"

    /** Pure: one broker result -> one tester line. */
    fun line(cmd: String, r: ShellResult?, ms: Long): Line = when (r) {
        is ShellResult.Ok -> Line(cmd, r.exitCode == 0 && r.stderr.isBlank(), r.source.ifBlank { "broker" }, r.exitCode, ms,
            (if (r.stderr.isNotBlank()) "stderr: " + r.stderr + "\n" else "") + r.stdout.lineSequence().filter { it.isNotBlank() }.take(3).joinToString("\n").ifBlank { "(no output)" })
        is ShellResult.Denied -> Line(cmd, false, "broker", null, ms, "refused by the allowlist: ${r.reason}")
        is ShellResult.Failed -> Line(cmd, false, "broker", null, ms, (if (r.timedOut) "timed out: " else "failed: ") + r.reason)
        ShellResult.NeedsArm -> Line(cmd, false, "none", null, ms, "no channel: ADB is not connected")
        null -> Line(cmd, false, "none", null, ms, "the broker threw")
    }

    fun run(ctx: Context): List<Line> {
        val out = ArrayList<Line>()
        val t0 = System.currentTimeMillis()
        val st = AdbReadiness.check(ctx, fresh = true)
        out += Line(STATUS_CMD, st.usable, st.via, null, System.currentTimeMillis() - t0, if (st.usable) "answered the shell uid" else st.why.ifBlank { "down" })
        for (cmd in COMMANDS) {
            val s = System.currentTimeMillis()
            val r = runCatching { ShellBroker.exec(ctx, ShellBroker.SELF, cmd) }.getOrNull()
            out += line(cmd, r, System.currentTimeMillis() - s)
        }
        return out
    }
}

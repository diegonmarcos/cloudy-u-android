package com.diegonmarcos.superapp.shellbroker

import android.content.Context
import com.diegonmarcos.superapp.adbdebug.ChannelAutoArm
import com.diegonmarcos.superapp.adbdebug.EmbeddedAdbChannel
import com.diegonmarcos.superapp.adbdebug.ShellChannels
import com.diegonmarcos.superapp.shellclient.ShellResult
import com.diegonmarcos.superapp.shellclient.ShellStatus
import com.diegonmarcos.superapp.shellclient.ShellWire
import java.io.File

/**
 * #1510 The SuperApp's one shell broker: [BrokerCore] over the embedded ADB / Shizuku ladder ([ShellChannels]) of
 * libs:shizuku-adb-debug-tools, with the allowlist from assets/shell-allowlist.json and an audit file in the app's files dir.
 * Entry points: [ShellBrokerProvider] and [ShellBrokerService] (other apps), and [exec] for the SuperApp's own features.
 */
object ShellBroker {
    const val SELF = "com.diegonmarcos.superapp"
    private const val ALLOWLIST_ASSET = "shell-allowlist.json"
    private const val SPOOL_DIR = "shell-stdin"
    private const val AUDIT_FILE = "shell-broker-audit.jsonl"
    private const val AUDIT_MAX_BYTES = 64 * 1024L
    private const val INTERNAL_TIMEOUT_MS = 60_000L
    private const val INTERNAL_MAX_BYTES = 8 * 1024 * 1024

    @Volatile private var core: BrokerCore? = null
    private val gate = ArmGate()

    private fun core(ctx: Context): BrokerCore = core ?: synchronized(this) {
        core ?: build(ctx.applicationContext).also { core = it }
    }

    private fun build(app: Context): BrokerCore {
        val list = Allowlist.parse(app.assets.open(ALLOWLIST_ASSET).bufferedReader().use { it.readText() })
        val file = File(app.filesDir, AUDIT_FILE)
        val audit = AuditLog(sink = AuditLog.Sink { line ->
            if (file.length() > AUDIT_MAX_BYTES) file.writeText("")   // bounded: the ring in memory is the recent view
            file.appendText(line + "\n")
        })
        val channel = object : BrokerChannel {
            override fun name(): String = ShellChannels.active(app)?.name() ?: "none"
            override fun ready(): Boolean = ShellChannels.active(app) != null
            override fun whyDown(): String = runCatching {
                val f = com.diegonmarcos.superapp.adbdebug.ChannelReader.read(app)
                when {
                    f.wirelessDebug == false -> "wireless debugging is off"
                    !f.adbPaired -> "channel not paired (pair it on the ADB Shell page)"
                    else -> "connection refused: paired, but not connected"
                }
            }.getOrDefault("no privileged channel is armed")
            override fun run(cmd: String, stdin: File?): String? {
                val ch = ShellChannels.active(app) ?: return null
                return if (stdin != null) ch.execWithStdin(app, cmd, stdin) else ch.exec(app, cmd)
            }
        }
        // One arm attempt per call at most, and only when the SuperApp's own switch allows it (ChannelAutoArm), gated by RetryBackoff.
        val arm = {
            val now = System.currentTimeMillis()
            if (!ChannelAutoArm.enabled(app) || !gate.mayTry(now)) false
            else EmbeddedAdbChannel.autoArm(app).first.also { ok -> if (ok) gate.succeeded() else gate.failed(now) }
        }
        return BrokerCore({ list }, channel, audit, tryArm = arm).also { c -> c.onChange = { onStatusChange?.invoke(it) } }
    }

    /** For the SuperApp's own features and the provider: [caller] is the resolved package. Blocking. */
    fun exec(ctx: Context, caller: String, cmd: String, timeoutMs: Long = ShellWire.DEFAULT_TIMEOUT_MS,
             maxBytes: Int = ShellWire.EXEC_MAX_BYTES, stdin: File? = null): ShellResult =
        core(ctx).also { it.reapIfIdle() }.exec(caller, cmd, timeoutMs, maxBytes, stdin)

    /** #1512 The stdin door ([BrokerCore.execStdin]): [input] is the client's descriptor, read for at most [size] bytes into the cache spool. */
    fun execStdin(ctx: Context, caller: String, cmd: String, input: java.io.InputStream, size: Long,
                  timeoutMs: Long = ShellWire.STDIN_DEFAULT_TIMEOUT_MS): ShellResult =
        core(ctx).also { it.reapIfIdle() }.execStdin(caller, cmd, input, size, timeoutMs, File(ctx.cacheDir, SPOOL_DIR))

    /**
     * The SuperApp's own features (X-Ray, Background policy, Super Saving): the command's output under the SuperApp's entry, or null
     * when it was refused, timed out or no channel is armed (the shape the old direct channel call had). Long budget: a batterystats dump.
     */
    fun execText(ctx: Context, cmd: String): String? =
        (exec(ctx, SELF, cmd, INTERNAL_TIMEOUT_MS, INTERNAL_MAX_BYTES) as? ShellResult.Ok)?.stdout

    /** An unenforced legacy caller (fleet IPC `adb/exec`): audited under the caller "legacy-ipc" until it moves to the client. */
    fun recordLegacy(ctx: Context, cmd: String, ok: Boolean, ms: Long, source: String) =
        core(ctx).note("legacy-ipc", cmd, if (ok) "legacy" else "legacy-fail", if (ok) 0 else -1, ms, source)

    /** The broker's change callback: called when a live check flips the verdict (up <-> down). Set by the home status strip. */
    @Volatile var onStatusChange: ((ShellStatus) -> Unit)? = null

    fun status(ctx: Context): ShellStatus = core(ctx).status()

    fun auditEntries(ctx: Context): List<AuditLog.Entry> = core(ctx).auditEntries()

    fun callerSummaries(ctx: Context): List<Pair<String, String>> = core(ctx).callerSummaries()

    /** Called by the service when it is unbound: drop the workers if idle. */
    fun reapIfIdle() { core?.reapIfIdle() }
}

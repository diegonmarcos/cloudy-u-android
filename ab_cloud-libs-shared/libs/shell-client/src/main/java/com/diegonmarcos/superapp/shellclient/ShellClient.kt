package com.diegonmarcos.superapp.shellclient

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * #1510 The thin client of the SuperApp's shell broker. It owns no channel: no pairing, no reconnect loop, no keep-alive,
 * no boot receiver. Every call is one provider call on the SuperApp (which starts its process), answered under the CALLER's
 * allowlist entry (the broker reads the caller from the binder uid, never from this client). An unarmed broker answers
 * [ShellResult.NeedsArm]; the UI then offers "Arm in SuperApp".
 *
 * Blocking: call off the main thread. Every call is bounded by [exec]'s timeout.
 */
class ShellClient(val transport: Transport) {

    /** One round trip to the broker; null = the broker could not be reached. Pure seam, so the rules are unit-tested. */
    fun interface Transport {
        fun call(method: String, cmd: String, extras: Map<String, String>): String?
        /** #1512 The stdin door: [stdin] is handed over as a file descriptor. Default = no such door. */
        fun callStdin(cmd: String, extras: Map<String, String>, stdin: java.io.File): String? = null
        /** #1682 The output door: the reply JSON and the bytes read from the pipe (at most [ShellWire.OUT_MAX_BYTES]). Default = no door. */
        fun callOut(cmd: String, extras: Map<String, String>): Pair<String?, ByteArray>? = null
    }

    /** `{exitCode, stdout, stderr, source}` or NeedsArm / Denied / Failed. */
    fun exec(cmd: String, timeoutMs: Long = ShellWire.DEFAULT_TIMEOUT_MS): ShellResult =
        ask(ShellWire.M_EXEC, cmd, timeoutMs, ShellWire.EXEC_MAX_BYTES)

    /** Bounded stream: the broker caps the output at [maxBytes]; [onLine] gets each line and returns false to stop early. */
    fun stream(cmd: String, maxBytes: Int = ShellWire.STREAM_MAX_BYTES, timeoutMs: Long = ShellWire.DEFAULT_TIMEOUT_MS,
               onLine: (String) -> Boolean): ShellResult {
        val r = ask(ShellWire.M_STREAM, cmd, timeoutMs, maxBytes.coerceIn(1, ShellWire.EXEC_MAX_BYTES))
        if (r is ShellResult.Ok) for (line in r.stdout.lineSequence()) if (!onLine(line)) break
        return r
    }

    /**
     * #1512 `exec` with [stdin] streamed to the command (`pm install -S <size>`, `pm install-write -S <size> ... -`). The input is
     * bounded ([ShellWire.STDIN_MAX_BYTES]; a bigger or empty file never reaches the broker), the call has its own longer timeout,
     * and the broker runs it only under the caller's `stdin` allowlist rule. The command carries no `2>&1`: the broker merges stderr.
     */
    fun execStdin(cmd: String, stdin: java.io.File, timeoutMs: Long = ShellWire.STDIN_DEFAULT_TIMEOUT_MS): ShellResult {
        if (cmd.isBlank()) return ShellResult.Denied("empty command")
        if (!stdin.isFile) return ShellResult.Failed("input file is missing")
        val size = stdin.length()
        ShellWire.stdinProblem(size)?.let { return ShellResult.Denied(it) }
        val t = ShellWire.clampStdinTimeout(timeoutMs)
        val extras = mapOf(ShellWire.K_TIMEOUT to t.toString(), ShellWire.K_MAX_BYTES to ShellWire.EXEC_MAX_BYTES.toString(),
            ShellWire.K_STDIN_SIZE to size.toString())
        return bounded(t) { transport.callStdin(cmd, extras, stdin) }
    }

    /**
     * #1682 `exec` whose stdout comes back through the output door, for answers over the provider's [ShellWire.EXEC_MAX_BYTES]
     * (X-Ray dumps, batterystats, package dumps). The broker caps it at the caller's `outMaxBytes`; a caller without one is Denied.
     */
    fun execOut(cmd: String, timeoutMs: Long = ShellWire.DEFAULT_TIMEOUT_MS): ShellResult {
        if (cmd.isBlank()) return ShellResult.Denied("empty command")
        val t = ShellWire.clampTimeout(timeoutMs)
        val extras = mapOf(ShellWire.K_TIMEOUT to t.toString())
        var bytes = ByteArray(0)
        val r = bounded(t) { transport.callOut(cmd, extras)?.let { (json, out) -> bytes = out; json } }
        return if (r is ShellResult.Ok) r.copy(stdout = String(bytes, Charsets.UTF_8)) else r
    }

    /** #1682 Ask the owner to drop its cached status (a page resumed, a setting changed); false = the owner did not answer. */
    fun invalidate(): Boolean = runCatching { transport.call(ShellWire.M_INVALIDATE, "", emptyMap()) != null }.getOrDefault(false)

    /** The broker's LIVE status: it runs a round trip through the real channel on every call (a few seconds of cache at most, broker side). */
    fun status(): ShellStatus {
        val json = runCatching { transport.call(ShellWire.M_STATUS, "", emptyMap()) }.getOrNull()
            ?: return ShellStatus(false, false, "none", "broker unreachable", System.currentTimeMillis(), "broker unreachable (the SuperApp did not answer)")
        return ShellWire.decodeStatus(json)
    }

    private fun ask(method: String, cmd: String, timeoutMs: Long, maxBytes: Int): ShellResult {
        if (cmd.isBlank()) return ShellResult.Denied("empty command")
        val t = ShellWire.clampTimeout(timeoutMs)
        val extras = mapOf(ShellWire.K_TIMEOUT to t.toString(), ShellWire.K_MAX_BYTES to maxBytes.toString())
        return bounded(t) { transport.call(method, cmd, extras) }
    }

    private fun bounded(t: Long, call: () -> String?): ShellResult {
        // The broker enforces the timeout; this bound is the grace that covers the binder hop itself.
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "shell-client").apply { isDaemon = true } }
        return try {
            val f = pool.submit(Callable { call() })
            ShellWire.decode(f.get(t + GRACE_MS, TimeUnit.MILLISECONDS))
        } catch (e: TimeoutException) {
            ShellResult.Failed("broker did not answer within ${t / 1000}s", timedOut = true)
        } catch (e: ExecutionException) {
            ShellResult.Failed("broker call failed: ${e.cause?.javaClass?.simpleName}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); ShellResult.Failed("interrupted")
        } finally { pool.shutdownNow() }
    }

    companion object {
        private const val GRACE_MS = 3_000L
        private const val OUT_DRAIN_MS = 10_000L

        /** The client over the real provider of the SuperApp. */
        fun over(ctx: Context): ShellClient {
            val app = ctx.applicationContext
            return ShellClient(object : Transport {
                override fun call(method: String, cmd: String, extras: Map<String, String>): String? {
                    val client = app.contentResolver.acquireUnstableContentProviderClient(ShellWire.AUTHORITY) ?: return null
                    return try {
                        val b = Bundle().apply { extras.forEach { (k, v) -> putString(k, v) } }
                        client.call(method, cmd, b)?.getString(ShellWire.K_JSON)
                    } finally { client.close() }
                }

                override fun callStdin(cmd: String, extras: Map<String, String>, stdin: java.io.File): String? {
                    val client = app.contentResolver.acquireUnstableContentProviderClient(ShellWire.AUTHORITY) ?: return null
                    val pfd = ParcelFileDescriptor.open(stdin, ParcelFileDescriptor.MODE_READ_ONLY)
                    return try {
                        val b = Bundle().apply {
                            extras.forEach { (k, v) -> putString(k, v) }
                            putParcelable(ShellWire.K_STDIN_FD, pfd)
                        }
                        client.call(ShellWire.M_EXEC_STDIN, cmd, b)?.getString(ShellWire.K_JSON)
                    } finally { runCatching { pfd.close() }; client.close() }
                }

                // #1682 The output door: the read end is drained on its own thread WHILE the owner writes (a pipe holds ~64 KB),
                // bounded at OUT_MAX_BYTES; our copy of the write end is closed after the call so the reader sees EOF.
                override fun callOut(cmd: String, extras: Map<String, String>): Pair<String?, ByteArray>? {
                    val client = app.contentResolver.acquireUnstableContentProviderClient(ShellWire.AUTHORITY) ?: return null
                    val (rd, wr) = ParcelFileDescriptor.createPipe().let { it[0] to it[1] }
                    val sink = java.io.ByteArrayOutputStream()
                    val reader = Thread({
                        runCatching { ParcelFileDescriptor.AutoCloseInputStream(rd).use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf); if (n < 0) break
                                val room = ShellWire.OUT_MAX_BYTES - sink.size(); if (room > 0) sink.write(buf, 0, minOf(n, room))
                            }
                        } }
                    }, "shell-client-out").apply { isDaemon = true; start() }
                    return try {
                        val b = Bundle().apply { extras.forEach { (k, v) -> putString(k, v) }; putParcelable(ShellWire.K_OUT_FD, wr) }
                        val json = client.call(ShellWire.M_EXEC_OUT, cmd, b)?.getString(ShellWire.K_JSON)
                        runCatching { wr.close() }
                        reader.join(OUT_DRAIN_MS)
                        json to sink.toByteArray()
                    } finally { runCatching { wr.close() }; client.close() }
                }
            })
        }

        fun exec(ctx: Context, cmd: String, timeoutMs: Long = ShellWire.DEFAULT_TIMEOUT_MS): ShellResult = over(ctx).exec(cmd, timeoutMs)
        fun execOut(ctx: Context, cmd: String, timeoutMs: Long = ShellWire.DEFAULT_TIMEOUT_MS): ShellResult = over(ctx).execOut(cmd, timeoutMs)
        fun invalidate(ctx: Context): Boolean = over(ctx).invalidate()
        fun status(ctx: Context): ShellStatus {
            if (!superAppInstalled(ctx)) return ShellStatus(false, false, "none", "Cloud C3 Webserver missing", System.currentTimeMillis(),
                "Cloud C3 Webserver is not installed (it owns the ADB shell): install it from the Store")
            return over(ctx).status()
        }

        fun superAppInstalled(ctx: Context): Boolean =
            runCatching { ctx.packageManager.getPackageInfo(ShellWire.BROKER_PKG, 0) }.isSuccess

        /** The explicit intent of "Arm in SuperApp": the SuperApp's declared, exported [ShellWire.ARM_ACTIVITY] (action kept for resolution checks). */
        fun armIntent(): Intent = Intent(ShellWire.ACTION_ARM).setClassName(ShellWire.BROKER_PKG, ShellWire.ARM_ACTIVITY)

        /** #1512 The stdin door over the real provider. Blocking, off the main thread. */
        fun execStdin(ctx: Context, cmd: String, stdin: java.io.File, timeoutMs: Long = ShellWire.STDIN_DEFAULT_TIMEOUT_MS): ShellResult =
            over(ctx).execStdin(cmd, stdin, timeoutMs)

        /** THE one way every app opens the owner's ADB Shell page: the declared exported entry, NEW_TASK, a toast (never silent) on failure; true = started. */
        fun openArmPage(ctx: Context): Boolean {
            if (!superAppInstalled(ctx)) { toast(ctx, "Cloud C3 Webserver is not installed: it owns the ADB shell. Install it from the Store."); return false }
            val i = armIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val r = runCatching { ctx.startActivity(i) }
            if (r.isFailure) toast(ctx, "Could not open Cloud C3 Webserver > ADB Shell (${r.exceptionOrNull()?.javaClass?.simpleName}). Update the Cloud C3 Webserver.")
            return r.isSuccess
        }

        private fun toast(ctx: Context, msg: String) {
            runCatching { android.widget.Toast.makeText(ctx.applicationContext, msg, android.widget.Toast.LENGTH_LONG).show() }
        }
    }
}

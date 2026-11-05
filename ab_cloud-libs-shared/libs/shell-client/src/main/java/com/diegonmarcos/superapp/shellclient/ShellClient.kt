package com.diegonmarcos.superapp.shellclient

import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * #1510 The thin client of the SuperApp's shell broker. It owns no channel: no pairing, no reconnect loop, no keep-alive,
 * no boot receiver. Every call is one provider call on the SuperApp (which starts its process), answered under the CALLER's
 * allowlist entry (the broker reads the caller from the binder uid, never from this client). An unarmed broker answers
 * [ShellResult.NeedsArm]; the UI then offers "Arm in SuperApp" ([arm]).
 *
 * Blocking: call off the main thread. Every call is bounded by [exec]'s timeout.
 */
class ShellClient(private val transport: Transport) {

    /** One round trip to the broker; null = the broker could not be reached. Pure seam, so the rules are unit-tested. */
    fun interface Transport {
        fun call(method: String, cmd: String, extras: Map<String, String>): String?
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

    fun status(): ShellStatus {
        val json = runCatching { transport.call(ShellWire.M_STATUS, "", emptyMap()) }.getOrNull()
            ?: return ShellStatus(false, false, "none", "SuperApp (the shell broker) is not installed or not reachable")
        return ShellWire.decodeStatus(json)
    }

    private fun ask(method: String, cmd: String, timeoutMs: Long, maxBytes: Int): ShellResult {
        if (cmd.isBlank()) return ShellResult.Denied("empty command")
        val t = ShellWire.clampTimeout(timeoutMs)
        val extras = mapOf(ShellWire.K_TIMEOUT to t.toString(), ShellWire.K_MAX_BYTES to maxBytes.toString())
        // The broker enforces the timeout; this bound is the grace that covers the binder hop itself.
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "shell-client").apply { isDaemon = true } }
        return try {
            val f = pool.submit(Callable { transport.call(method, cmd, extras) })
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

        /** The client over the real provider of the SuperApp. */
        fun over(ctx: Context): ShellClient {
            val app = ctx.applicationContext
            return ShellClient { method, cmd, extras ->
                val client = app.contentResolver.acquireUnstableContentProviderClient(ShellWire.AUTHORITY)
                if (client == null) null else try {
                    val b = Bundle().apply { extras.forEach { (k, v) -> putString(k, v) } }
                    client.call(method, cmd, b)?.getString(ShellWire.K_JSON)
                } finally { client.close() }
            }
        }

        fun exec(ctx: Context, cmd: String, timeoutMs: Long = ShellWire.DEFAULT_TIMEOUT_MS): ShellResult = over(ctx).exec(cmd, timeoutMs)
        fun status(ctx: Context): ShellStatus = over(ctx).status()

        /** "Arm in SuperApp": opens the SuperApp's re-arm flow (the ADB Shell page, or the Wireless debugging switch). */
        fun arm(ctx: Context): Boolean {
            val i = Intent(ShellWire.ACTION_ARM).setPackage(ShellWire.BROKER_PKG)
            if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching { ctx.startActivity(i) }.isSuccess
        }
    }
}

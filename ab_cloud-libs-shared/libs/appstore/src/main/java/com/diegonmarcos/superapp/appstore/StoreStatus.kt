package com.diegonmarcos.superapp.appstore

import android.content.Context
import com.diegonmarcos.superapp.shellclient.AdbRecheck
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The Store bar's two status controls, measured rather than inferred. The rules (mapping) are [ControlStatus]'s; this is the
 * device glue: the shell broker's status (one provider call, cached for [TTL_MS]), always run off the main thread, plus the Play
 * Protect read. #1512: the channel belongs to the SuperApp's broker, so there is no probe of an adb link of our own.
 *
 * #1556 THE ADB CHIP MUST REACH A DEFINITE STATE AND STOP. [refresh] can never leave the status null ("checking") once it has
 * tried: a throw or a hung provider call resolves to a definite unreachable status within [PROBE_BUDGET_MS], and the done callback
 * always fires, so the caller's recomposition settles instead of re-firing. Re-checks are gated by [AdbRecheck] (the 30 s floor),
 * so resume and the adb-setting observer cannot drive a probe loop.
 */
internal object StoreStatus {
    private const val TTL_MS = 3_000L
    /** A whole probe (the broker IPC round trip incl. its own adb timeouts) may take this long before it is called unreachable. */
    private const val PROBE_BUDGET_MS = 9_000L

    @Volatile private var cached: ControlStatus.ChannelStatus? = null
    @Volatile private var at = 0L
    /** When the last probe STARTED: the floor is measured from here, so a hung probe still blocks a second for 30 s. */
    @Volatile private var lastProbeAt = 0L
    @Volatile private var ppViaShell: ControlStatus.Tri? = null
    private val running = AtomicBoolean(false)

    fun channel(@Suppress("UNUSED_PARAMETER") ctx: Context): ControlStatus.ChannelStatus? = cached
    fun fresh(@Suppress("UNUSED_PARAMETER") ctx: Context): Boolean = cached != null && System.currentTimeMillis() - at < TTL_MS

    /** #1556 Whether a re-probe may start now: never while one is in flight, and never more than once per [AdbRecheck.FLOOR_MS]. */
    fun dueForRecheck(now: Long = System.currentTimeMillis()): Boolean = AdbRecheck.due(lastProbeAt, now, running.get())

    /** Cheap provider read; falls back to the last shell-read answer when the provider has none. */
    fun playProtect(ctx: Context): ControlStatus.Tri {
        val t = ControlStatus.playProtect(StorePlayProtect.reading(ctx))
        return if (t == ControlStatus.Tri.UNKNOWN) ppViaShell ?: t else t
    }

    fun invalidate() { cached = null; ppViaShell = null }

    /**
     * Re-probe off the main thread (single flight), then [done] (call-site posts to the UI).
     *
     * #1556 Bounded and terminal: the broker read runs on its own executor with a [PROBE_BUDGET_MS] deadline; a timeout or any
     * throw becomes a definite "broker unreachable" status (never left null / "checking"), [cached] and [at] are always written,
     * and [done] always runs. So one probe resolves the chip once; it does not hang in "checking" and its completion does not
     * re-arm another probe (the caller gates on [dueForRecheck]).
     */
    fun refresh(ctx: Context, done: () -> Unit) {
        if (!running.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        lastProbeAt = System.currentTimeMillis()
        thread(name = "store-status-probe") {
            val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "store-status-broker").apply { isDaemon = true } }
            val ch: ControlStatus.ChannelStatus = try {
                pool.submit<ControlStatus.ChannelStatus> { ControlStatus.fromBroker(StoreShell.status(app)) }
                    .get(PROBE_BUDGET_MS, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                ControlStatus.unreachable("the shell broker did not answer within ${PROBE_BUDGET_MS / 1000}s")
            } catch (e: Throwable) {
                ControlStatus.unreachable("the shell broker could not be reached (${(e.cause ?: e).javaClass.simpleName})")
            } finally { pool.shutdownNow() }
            try {
                cached = ch; at = System.currentTimeMillis()
                if (ch.shellUsable && ControlStatus.playProtect(StorePlayProtect.reading(app)) == ControlStatus.Tri.UNKNOWN)
                    ppViaShell = ControlStatus.playProtect(StorePlayProtect.readingViaShell(app))
            } finally { running.set(false); done() }
        }
    }
}

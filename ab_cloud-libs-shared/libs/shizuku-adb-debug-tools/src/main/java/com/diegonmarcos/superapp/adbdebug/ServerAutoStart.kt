package com.diegonmarcos.superapp.adbdebug

import android.content.Context
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * (#1709) The automatic chain of a profile, run with single-flight and bounded retries. No Android in here: the device
 * side is a [ConnectExecutor], so fakes drive it in the JVM tests.
 *
 * One run = up to 1 + backoff.size attempts of [ConnectPlan.run] for the profile's own chain. Only a failure of a
 * transient step is retried (the connect, a start, the launch, the round trip); a step that needs the person (a new
 * pairing code) or a switch only the person can flip (Developer options) is reported, not hammered. Whatever the
 * outcome, an embedded session this chain opened is CLOSED at the end (pairing kept), and every attempt is recorded.
 */
class AutoStartCore(
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val backoffMs: List<Long> = listOf(2_000L, 6_000L),
) {
    private val busy = AtomicBoolean(false)
    fun inFlight(): Boolean = busy.get()

    /** Null when another run is already in flight (nothing was started). */
    fun run(
        mode: ChannelMode, exec: ConnectExecutor, record: (String) -> Unit, log: ChannelLog? = null,
        ownsServer: Boolean = true, onUpdate: (List<StepRun>) -> Unit = {},
    ): ConnectOutcome? {
        if (!busy.compareAndSet(false, true)) return null
        try {
            val total = backoffMs.size + 1
            var out: ConnectOutcome? = null
            for (n in 1..total) {
                val o = ConnectPlan.run(mode, LaunchVia.ADB, exec, log, ownsServer, onUpdate)
                out = o
                record("attempt $n/$total: " + describe(o))
                if (o.status != ConnectStatus.FAILED || o.failedStep !in RETRY || n == total) break
                sleep(backoffMs[n - 1])
            }
            val last = out!!
            // Close what this chain opened, also when it stopped early (the launch failed: the session must not stay).
            val closes = ConnectPlan.steps(mode, LaunchVia.ADB, ownsServer).contains(StepId.CLOSE_EMBEDDED)
            if (closes && last.steps.none { it.id == StepId.CLOSE_EMBEDDED && (it.state == StepState.DONE || it.state == StepState.SKIPPED) } &&
                runCatching { exec.facts().adbConnected }.getOrDefault(false)) {
                val c = runCatching { exec.run(StepId.CLOSE_EMBEDDED) }.getOrElse { StepResult(false, it.message ?: "error") }
                record("embedded session " + if (c.ok) "closed" else "NOT closed (${c.detail})")
            }
            return last
        } finally { busy.set(false) }
    }

    companion object {
        val RETRY = setOf(StepId.ADB_CONNECT, StepId.SHIZUKU_START, StepId.LAUNCH_SERVER, StepId.VERIFY)

        /** One line of the real outcome: the last step that ran and what it said. */
        fun describe(o: ConnectOutcome): String = when (o.status) {
            ConnectStatus.SUCCESS -> "ok - " + (o.steps.lastOrNull { it.state == StepState.DONE && it.detail.isNotBlank() }?.let { "${it.id.label}: ${it.detail}" } ?: "every step passed")
            else -> o.explanation ?: "stopped"
        }
    }
}

/**
 * The device-side owner of the automatic chain for the Local server profile. [request] is the non-blocking entry (a
 * command that found the channel down, app start, Wireless debugging coming on); [runBlocking] is for a worker that is
 * already off the main thread (the boot worker). Both are single-flight through one [AutoStartCore].
 */
object LocalServerAutoStart {
    private val core = AutoStartCore()
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "local-server-autostart").apply { isDaemon = true } }
    @Volatile private var coolUntil = 0L
    @Volatile var lastSteps: List<StepRun> = emptyList(); private set
    @Volatile var lastOutcome: ConnectOutcome? = null; private set
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners.addIfAbsent(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    /** #1743 A run started by a button publishes its live steps so every page (Status step list, Home table) shows them. */
    fun publish(steps: List<StepRun>) { lastSteps = steps; listeners.forEach { l -> runCatching { l() } } }

    /** Pure: how long a finished run keeps an implicit "command" trigger quiet. */
    fun cooldownMs(status: ConnectStatus): Long = when (status) {
        ConnectStatus.SUCCESS -> 0L
        ConnectStatus.FAILED -> 30_000L
        ConnectStatus.WAITING_FOR_USER -> 300_000L   // the pairing prompt is up: do not post it again every lookup
    }

    /** This app runs the chain: Local server profile, and it owns a server (a client's server is the owner's). */
    fun applies(ctx: Context): Boolean = ChannelModePrefs.ownsServer() && ChannelModePrefs.current(ctx) == ChannelMode.LOCAL_SERVER

    fun inFlight(): Boolean = core.inFlight()

    /**
     * #1834 Pure: may this trigger run the chain? Only a tap ("button") is explicit. Every implicit trigger (app-start, boot, command,
     * wireless-debugging-on) needs the keep-alive switch ([ChannelAutoArm]) ON: with it OFF the chain would end in the pairing
     * notification, so nothing implicit may start it (the user asked for neither the shell nor its keep-alive).
     */
    fun mayRun(reason: String, keepAliveOn: Boolean): Boolean = reason == "button" || keepAliveOn

    /** #1833 Pure: may a run started for [reason] post the pairing notification? Only a tap. An implicit run with the keep-alive ON
     *  still reconnects a paired device (mDNS connect, server launch), but stops at the pairing step with [PAIR_NOT_IMPLICIT]. */
    fun mayPair(reason: String): Boolean = reason == "button"

    const val PAIR_NOT_IMPLICIT = "not paired: pairing is only started by a tap (Pair / Connect), never by an automatic run"

    private fun gated(ctx: Context, reason: String) = !mayRun(reason, runCatching { ChannelAutoArm.enabled(ctx) }.getOrDefault(false))

    /** Fire and forget; never blocks the caller (safe on the main thread). [reason] "command" honours the cooldown. */
    fun request(ctx: Context, reason: String) {
        if (!applies(ctx) || core.inFlight() || gated(ctx, reason)) return
        if (reason == "command" && System.currentTimeMillis() < coolUntil) return
        val app = ctx.applicationContext
        runCatching { pool.execute { runCatching { runBlocking(app, reason) } } }
    }

    /** Runs the chain on the calling thread; refuses on the main thread. Null when nothing ran. */
    fun runBlocking(ctx: Context, reason: String): ConnectOutcome? {
        val app = ctx.applicationContext
        if (!applies(app)) return null
        if (gated(app, reason)) { ChannelLog.shared.add("server", "auto-start ($reason) not run: keep-alive is off (no pairing prompt unless tapped)"); return null }
        if (Looper.getMainLooper() != null && Looper.myLooper() == Looper.getMainLooper()) {
            ChannelLog.shared.add("server", "auto-start ($reason) refused on the main thread"); return null
        }
        if (LocalShellChannel.isReady(app) && LocalShellChannel.probe(app)) return null
        ChannelLog.shared.add("server", "auto-start ($reason): local server does not answer, running the chain")
        val out = core.run(ChannelMode.LOCAL_SERVER, DeviceConnectExecutor(app, explicit = mayPair(reason)),
            { AdbShellBootstrap.record(app, "auto ($reason) $it") }, ChannelLog.shared, true) { lastSteps = it; listeners.forEach { l -> runCatching { l() } } }
            ?: return null
        lastOutcome = out
        coolUntil = System.currentTimeMillis() + cooldownMs(out.status)
        listeners.forEach { runCatching { it() } }
        return out
    }

    /** One line for a button. */
    fun summary(o: ConnectOutcome?): String = o?.let { AutoStartCore.describe(it) } ?: "another launch is already running"
}

/**
 * #1743 The ONE chain runner behind every Connect / Reconnect button: the profile's whole chain (every step re-probes the real state
 * through [ConnectExecutor.facts], nothing trusted from a cache), single-flight, a failed transient step retried with backoff, the
 * exact reason in the outcome. The lib page, the cockpit and the owner Status page all call this.
 */
object ChainRunner {
    private val core = AutoStartCore()
    fun inFlight(): Boolean = core.inFlight()

    /** Null when another run is in flight. [onUpdate] gets the live step list. */
    fun run(mode: ChannelMode, exec: ConnectExecutor, log: ChannelLog?, ownsServer: Boolean, onUpdate: (List<StepRun>) -> Unit = {}): ConnectOutcome? =
        core.run(mode, exec, { line -> log?.add("connect", line) }, log, ownsServer) { steps -> LocalServerAutoStart.publish(steps); onUpdate(steps) }
}

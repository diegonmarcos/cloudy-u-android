package com.diegonmarcos.superapp.adbdebug

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * What the Store bar's two device controls SAY, as pure rules (no Android), so the
 * mapping and the probe timeout are unit-testable. The view only renders these.
 */
object ControlStatus {

    /** A reading the device may not give: UNKNOWN is a state, not a default. */
    enum class Tri { ON, OFF, UNKNOWN }

    /** The three verifier values as the device STORES them; null = no such key (or unreadable).
     *  PackageVerifier.state cannot say that - its defaults make absent and consented look alike. */
    data class Reading(val consent: Int?, val enable: Int?, val adb: Int?)

    /** `settings get` prints "null" for an absent key; anything non-numeric is no answer. */
    fun parseSetting(out: String?): Int? = out?.trim()?.toIntOrNull()

    /** Play Protect's install scan. Any key still arming it reads ON (GMS re-arms one at a
     *  time); none readable at all reads UNKNOWN rather than a default dressed up as a fact. */
    fun playProtect(r: Reading): Tri {
        val v = listOf(r.consent, r.enable, r.adb)
        if (v.all { it == null }) return Tri.UNKNOWN
        val armed = (r.consent != null && r.consent >= 0) ||
            (r.enable != null && r.enable != 0) || (r.adb != null && r.adb != 0)
        return if (armed) Tri.ON else Tri.OFF
    }

    enum class Channel { UP, DOWN, NOT_PAIRED }

    /** [via] = the channel that ran `id` as the shell (it may answer while the adb link is DOWN: the
     *  local server outlives Wireless debugging). [reason] = one line saying why it is not UP. */
    data class ChannelStatus(val state: Channel, val via: String? = null, val shizuku: ShizukuState? = null,
                             val reason: String? = null) {
        /** Some channel still executes as the shell (gates shell work, never the colour). */
        val shellUsable: Boolean get() = state == Channel.UP || via != null
    }

    /**
     * #1442 The Wireless Dbg state from LIVE facts only. [echoOk] = a `echo` round trip over the paired
     * adb connection came back within the timeout - the only thing that makes it UP (green). A stored
     * "connected" flag, a cached state or the local server being alive never does. Paired but silent is
     * DOWN (amber) with the reason; never paired is NOT_PAIRED (red).
     */
    fun linkStatus(paired: Boolean, echoOk: Boolean, shellVia: String?, wirelessDebugOn: Boolean?): ChannelStatus = when {
        echoOk -> ChannelStatus(Channel.UP, shellVia ?: ChannelSelector.EMBEDDED)
        paired -> ChannelStatus(Channel.DOWN, shellVia, reason =
            (if (wirelessDebugOn == false) "Wireless debugging is off" else "paired, adb not answering (port changed?)") +
                (shellVia?.let { "; $it still runs commands" } ?: ""))
        else -> ChannelStatus(Channel.NOT_PAIRED, shellVia, reason = "not paired")
    }

    /** The chip's one line: green says the route, the others say why and what a tap does. */
    fun label(s: ChannelStatus, wirelessDebugOn: Boolean): String = when (s.state) {
        Channel.UP -> ChannelSelector.label(s.via, down = false)
        else -> "Wireless Dbg: ${s.reason ?: "down"} - " + when (channelAction(s, wirelessDebugOn)) {
            Action.PAIR -> "Pair"
            Action.OPEN_SETTINGS -> "Open settings"
            else -> "Reconnect"
        }
    }

    enum class Action { OPEN_SETTINGS, RECONNECT, PAIR, OPEN_SHIZUKU, REQUEST_SHIZUKU }

    /** What a tap on the channel control does. Reconnect needs Wireless Debugging on to
     *  have anything to discover, so with it off the useful step is the switch itself. */
    fun channelAction(s: ChannelStatus, wirelessDebugOn: Boolean, mode: ChannelMode = ChannelMode.LOCAL_SERVER): Action = when {
        mode == ChannelMode.SHIZUKU ->
            if (s.shizuku == ShizukuState.PERMISSION_NEEDED) Action.REQUEST_SHIZUKU else Action.OPEN_SHIZUKU
        s.state == Channel.UP -> Action.OPEN_SETTINGS
        s.state == Channel.DOWN -> if (wirelessDebugOn) Action.RECONNECT else Action.OPEN_SETTINGS
        else -> Action.PAIR
    }

    /** `id` output proves a shell-level authorisation: uid 2000 (shell), or 0 under root. */
    internal fun isShellUid(idOutput: String?): Boolean {
        val uid = idOutput?.let { Regex("""uid=(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        return uid == 2000 || uid == 0
    }

    /** The two questions the probe asks the device. Injected so a fake can stand in. */
    interface Backend {
        /** Was a privileged channel ever paired on this install. */
        fun paired(): Boolean
        /** (channel name, output of `id`) from the first ready channel, null if none is. Blocking. */
        fun id(): Pair<String, String?>?
        /** Shizuku mode only: where the external app stands (null in every other mode). */
        fun shizukuState(): ShizukuState? = null
        /** A real `echo` round trip over the paired adb connection (bounded). Not proven = false. Blocking. */
        fun adbEcho(): Boolean = false
        /** The Wireless debugging switch (null = unreadable). */
        fun wirelessDebugOn(): Boolean? = null
    }

    /**
     * Live round trip, cached. [get] blocks (at most [timeoutMs]) so call it OFF the main
     * thread; a wedged channel that accepts and never answers resolves to DOWN at the
     * deadline instead of hanging the bar. Results are reused for [ttlMs].
     */
    class Probe(
        private val backend: Backend,
        private val timeoutMs: Long = 4_000,
        private val ttlMs: Long = 5_000,
        private val clock: () -> Long = System::currentTimeMillis,
    ) {
        private val pool: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "store-channel-probe").apply { isDaemon = true } }
        private var at = Long.MIN_VALUE
        private var cached: ChannelStatus? = null

        /** The last answer, without probing. */
        @Synchronized fun peek(): ChannelStatus? = cached

        /** True while the cached answer is younger than the TTL (a render uses it to decide whether to re-probe). */
        @Synchronized fun fresh(): Boolean = cached != null && clock() - at < ttlMs

        @Synchronized fun invalidate() { cached = null }

        @Synchronized fun get(force: Boolean = false): ChannelStatus {
            val c = cached
            if (!force && c != null && clock() - at < ttlMs) return c
            val s = probeNow()
            cached = s; at = clock()
            return s
        }

        private fun <T> timed(block: () -> T): T? {
            val f = pool.submit(Callable { block() })
            return try { f.get(timeoutMs, TimeUnit.MILLISECONDS) }
                catch (_: TimeoutException) { f.cancel(true); null }
                catch (_: Exception) { null }
        }

        private fun probeNow(): ChannelStatus {
            val got = timed { backend.id() }
            val shellVia = got?.takeIf { isShellUid(it.second) }?.first
            val sz = runCatching { backend.shizukuState() }.getOrNull()
            // Shizuku mode: its own four-way state; "up" that did not answer is not up.
            if (sz != null) return if (shellVia != null) ChannelStatus(Channel.UP, shellVia, ShizukuState.UP)
                else ChannelStatus(Channel.DOWN, null, if (sz == ShizukuState.UP) ShizukuState.NOT_RUNNING else sz)
            val echo = timed { backend.adbEcho() } == true
            val paired = runCatching { backend.paired() }.getOrDefault(false)
            val wd = runCatching { backend.wirelessDebugOn() }.getOrNull()
            return linkStatus(paired, echo, if (echo) (shellVia ?: ChannelSelector.EMBEDDED) else shellVia, wd)
        }
    }
}

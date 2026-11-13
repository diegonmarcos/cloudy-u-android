package com.diegonmarcos.superapp.system

import android.content.Context
import com.diegonmarcos.superapp.shellclient.ShellClient
import com.diegonmarcos.superapp.shellclient.ShellStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * #1678 THE one answer to "is ADB usable now?" inside the SuperApp (#1682 P3 step 5: lives in the client-safe libs:adb-readiness, over [ShellClient.status]; the host installs [connector] / [hostOpener]). Every needs-ADB place (status dropdown rows, X-Ray, Location use,
 * the permission row / dashboard, Configs pages, badges) reads [state] / [check] here, never a channel class or a flag of its own
 * (guard test/test-adb-readiness-one-source.sh).
 *
 * The verdict is the broker's LIVE status ([ShellClient.status]: an `id -u` round trip over the channel the commands really run on,
 * cached 3 s at most), the same state the ADB Shell page draws. No loop and no timer: it is checked when a consumer asks (opening the
 * dropdown). The ADB OWNER's process also pushes a flip to [state] / the listeners ([publish], fed by libs:adb-shell-core's
 * AdbReadinessOwner through [ownerInstall]); a client-only app has no push, it re-checks when asked.
 *
 * Root cause it fixes: the dropdown fired every ADB probe at once; the broker runs at most 3 calls and answered the rest "busy", and
 * every non-OK result was printed as "needs ADB: arm the channel" while the channel was connected. Probes now queue
 * ([SystemGroups.adb]) and a refusal is told apart from a real "no channel".
 */
object AdbReadiness {

    /** [usable] = a shell command runs now; [why] = what is missing when it does not (empty when usable). */
    data class State(val usable: Boolean, val why: String, val via: String, val checkedAt: Long) {
        /** The row text of a needs-ADB line: says what is missing and what a tap does. */
        fun linkText(): String = if (usable) "ADB connected ($via)" else "ADB off" + (if (why.isBlank()) "" else ": $why") + " - tap to connect"
    }

    val UNKNOWN = State(false, "not checked yet", "none", 0L)

    private val flow = MutableStateFlow(UNKNOWN)
    val state: StateFlow<State> = flow.asStateFlow()

    private val listeners = CopyOnWriteArrayList<(ShellStatus) -> Unit>()

    /** Pure: the broker's live status -> the readiness verdict. */
    fun of(s: ShellStatus): State = State(s.armed, if (s.armed) "" else s.reason.ifBlank { s.detail }, s.channel, s.checkedAt)

    /** The owner process hooks its broker's change callback here ([publish] is the sink); unset in a client-only app. */
    @Volatile var ownerInstall: (() -> Unit)? = null
    /** The owner's non-blocking channel lookup (a pure read); unset in a client-only app (then only the last verdict counts). */
    @Volatile var ownerUsableNow: ((Context) -> Boolean)? = null

    /** Idempotent: lets the owner side (if any) wire its push into [publish]. Never throws. */
    @Synchronized fun install() {
        runCatching { ownerInstall?.invoke() }
    }

    fun publish(st: ShellStatus) {
        flow.value = of(st)
        listeners.forEach { l -> runCatching { l(st) } }
    }

    /** A screen that shows ADB state (the status strip) listens here instead of taking the broker callback. */
    fun addListener(l: (ShellStatus) -> Unit) { install(); listeners.addIfAbsent(l) }
    /** Tests: register a listener without touching the broker (no Context). */
    fun listeners_forTest(l: (ShellStatus) -> Unit) { listeners.addIfAbsent(l) }
    fun removeListener(l: (ShellStatus) -> Unit) { listeners.remove(l) }

    /** Blocking (a round trip, at most a few seconds): call off the main thread. [fresh] drops the 3 s cache first. Never throws. */
    fun check(ctx: Context, fresh: Boolean = false): State {
        install()
        return runCatching {
            if (fresh) ShellClient.invalidate(ctx)
            val st = ShellClient.status(ctx)
            of(st).also { flow.value = it }
        }.getOrElse { State(false, "status check failed (${it.javaClass.simpleName})", "none", System.currentTimeMillis()) }
    }

    /**
     * Non-blocking answer for main-thread callers (a Compose label, a toast gate): the last live verdict when it is under
     * [FRESH_MS] old, else the owner's channel lookup when this process is the owner (a pure read, no round trip), else false. Never throws.
     */
    fun usableNow(ctx: Context): Boolean {
        val s = flow.value
        if (s.checkedAt > 0 && System.currentTimeMillis() - s.checkedAt in 0 until FRESH_MS) return s.usable
        return runCatching { ownerUsableNow?.invoke(ctx) == true }.getOrDefault(false)
    }

    private const val FRESH_MS = 10_000L

    /**
     * The needs-ADB link: open the SuperApp's ADB Shell page and start Connect (auto-fix, the #1647 default) in the background, then
     * re-check so every consumer refreshes. [open] opens the page (the caller knows its host); never throws.
     */
    fun connect(ctx: Context, open: () -> Unit) {
        runCatching(open)
        val app = ctx.applicationContext
        Thread({
            runCatching { connector?.invoke(app) }
            check(app, fresh = true)
        }, "adb-readiness-connect").apply { isDaemon = true }.start()
    }

    /**
     * #1682 P2a: the host app's Connect chain (the SuperApp's AdbCockpitOps.connect today, the webserver's owner from P2b). Installed
     * once by the host at process start ([App.onCreate]); unset = Connect only re-checks (never throws).
     */
    @Volatile var connector: ((Context) -> Unit)? = null

    /** #1682 P2a: the host's in-app page opener (inside the SuperApp shell: its Configs page); returns false when [ctx] is not its host. */
    @Volatile var hostOpener: ((Context) -> Boolean)? = null

    /** Open the ADB Shell page from any context: inside the host its own page, anywhere else the owner app's page
     *  ([ShellClient.openArmPage]). Never throws. */
    fun openAdbShell(ctx: Context) {
        val inHost = runCatching { hostOpener?.invoke(ctx) == true }.getOrDefault(false)
        if (!inHost) runCatching { ShellClient.openArmPage(ctx) }
    }
}

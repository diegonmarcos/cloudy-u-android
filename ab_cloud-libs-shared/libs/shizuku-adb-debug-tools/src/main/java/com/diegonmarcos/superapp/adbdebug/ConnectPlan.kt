package com.diegonmarcos.superapp.adbdebug

/**
 * Who launches the optional loopback server when its Restart button is tapped in AUTO mode (the one mode that
 * is a ladder of every channel). Local server mode never reads it: that mode is the embedded adb channel alone.
 */
enum class LaunchVia(val id: String, val title: String) {
    ADB("adb", "Embedded adb"),
    SHIZUKU("shizuku", "Shizuku");

    companion object { fun parse(id: String?): LaunchVia = values().firstOrNull { it.id == id } ?: ADB }
}

/** One step of the Connect sequence. */
enum class StepId(val label: String) {
    DEV_OPTIONS("Developer options on"),
    WIRELESS_DEBUG("Wireless debugging on"),
    PAIR("Pair with code"),
    ADB_CONNECT("Connect embedded adb (mDNS)"),
    SHIZUKU_RUNNING("Shizuku running"),
    SHIZUKU_PERMISSION("Shizuku permission"),
    VERIFY("Round trip"),
}

enum class StepState { PENDING, RUNNING, DONE, SKIPPED, WAITING, FAILED }

data class StepRun(val id: StepId, val state: StepState = StepState.PENDING, val detail: String = "")

/** What a step's executor reports. [needsUser] = the step started something only the person can finish. */
data class StepResult(val ok: Boolean, val detail: String = "", val needsUser: Boolean = false)

/** The device side of the sequence. Fakes stand in for it in tests. */
interface ConnectExecutor {
    /** A fresh reading, taken before each step so an already-satisfied step is skipped. */
    fun facts(): ChannelFacts
    fun run(step: StepId): StepResult
}

enum class ConnectStatus { SUCCESS, FAILED, WAITING_FOR_USER }

data class ConnectOutcome(
    val steps: List<StepRun>,
    val status: ConnectStatus,
    val failedStep: StepId? = null,
    /** Which step stopped the run and what to do about it; null on success. */
    val explanation: String? = null,
)

/** One way to connect in a mode, as the page lists it. */
data class Method(val title: String, val detail: String)

/**
 * The Connect sequence as a step machine. [steps] decides WHICH steps a mode runs (the method that
 * applies); [run] walks them in order, skipping the ones already true, stopping at the first one that
 * fails or needs the person, and saying which step it was and why.
 *
 * Each mode is ONE self-contained channel and its chain holds only that channel's real prerequisites:
 *  - Shizuku: the Shizuku service and its permission. Nothing of adb.
 *  - Embedded adb, Local server, Auto: Developer options, Wireless debugging, the pairing, the adbd
 *    connection. Nothing of Shizuku.
 * The chain ends on a real shell round trip (VERIFY). There is no "start the server" step: once the
 * adbd connection is up the shell channel exists and commands run over it. The loopback app_process
 * server is an optional extra started only from its own button, never by Connect, Reconnect or Auto-fix.
 */
object ConnectPlan {

    /** The methods that apply to [mode] (the page shows these and nothing else). [via] is not part of any chain. */
    @Suppress("UNUSED_PARAMETER")
    fun methods(mode: ChannelMode, via: LaunchVia = LaunchVia.ADB, ownsServer: Boolean = true): List<Method> {
        val pair = Method("Pair with code", "Once: type the code into the notification (Developer options > Wireless debugging > Pair device with pairing code).")
        val mdns = Method("Connect via mDNS", "Finds the wireless-debugging port by itself; no port to type.")
        val direct = Method("Run over the adb session", "Commands run directly over the adbd connection on 127.0.0.1; nothing else is started.")
        return when (mode) {
            ChannelMode.SHIZUKU ->
                listOf(Method("Shizuku only", "Start the Shizuku service in its own app, then allow this app; nothing else is used."))
            ChannelMode.EMBEDDED_ONLY, ChannelMode.LOCAL_SERVER, ChannelMode.AUTO -> listOf(pair, mdns, direct)
        }
    }

    /** The steps [mode] runs, in order: the prerequisites of that mode's own channel, then the round trip. */
    @Suppress("UNUSED_PARAMETER")
    fun steps(mode: ChannelMode, via: LaunchVia = LaunchVia.ADB, ownsServer: Boolean = true): List<StepId> = when (mode) {
        ChannelMode.SHIZUKU -> SHIZUKU_CHAIN + StepId.VERIFY
        ChannelMode.EMBEDDED_ONLY, ChannelMode.LOCAL_SERVER, ChannelMode.AUTO -> ADB_CHAIN + StepId.VERIFY
    }

    /** The embedded adb / wireless-debugging channel's prerequisites. */
    val ADB_CHAIN = listOf(StepId.DEV_OPTIONS, StepId.WIRELESS_DEBUG, StepId.PAIR, StepId.ADB_CONNECT)
    /** The Shizuku channel's prerequisites. */
    val SHIZUKU_CHAIN = listOf(StepId.SHIZUKU_RUNNING, StepId.SHIZUKU_PERMISSION)

    /** Is [step] already true in [f]; such a step is skipped. VERIFY is never skipped. */
    fun satisfied(step: StepId, f: ChannelFacts): Boolean = when (step) {
        StepId.DEV_OPTIONS -> f.devOptions == true
        StepId.WIRELESS_DEBUG -> f.wirelessDebug == true
        StepId.PAIR -> f.adbPaired
        StepId.ADB_CONNECT -> f.adbConnected
        StepId.SHIZUKU_RUNNING -> f.shizukuRunning
        StepId.SHIZUKU_PERMISSION -> f.shizukuGranted
        StepId.VERIFY -> false
    }

    /** The sentence shown when [step] stopped the run. [detail] is what the executor said. */
    fun explain(step: StepId, detail: String, waiting: Boolean): String {
        val tail = if (detail.isBlank()) "" else " ($detail)"
        return "Stopped at \"${step.label}\": " + when (step) {
            StepId.DEV_OPTIONS -> "Developer options are off. Open About phone and tap Build number seven times, then Connect again.$tail"
            StepId.WIRELESS_DEBUG -> "Wireless debugging is off. Turn it on in Developer options (and join Wi-Fi), then Connect again.$tail"
            StepId.PAIR ->
                if (waiting) "pairing is waiting for you: open Wireless debugging > Pair device with pairing code and type the 6-digit code into the notification. The connection finishes by itself."
                else "pairing could not start. Allow notifications for this app (the code is typed into one) and try again.$tail"
            StepId.ADB_CONNECT -> "no wireless-debugging service answered. It must be ON and this phone on Wi-Fi; if the pairing was revoked, pair again.$tail"
            StepId.SHIZUKU_RUNNING -> "Shizuku is not running. Open Shizuku and start its service, then Connect again.$tail"
            StepId.SHIZUKU_PERMISSION -> "Shizuku has not allowed this app. Approve its prompt, then Connect again.$tail"
            StepId.VERIFY -> "the round trip got no shell answer.$tail"
        }
    }

    /**
     * Walk the plan. [onUpdate] gets the whole step list after every change (the page redraws from it).
     * Steps after the stopping one stay PENDING.
     */
    fun run(
        mode: ChannelMode, via: LaunchVia, exec: ConnectExecutor,
        log: ChannelLog? = null, ownsServer: Boolean = true, onUpdate: (List<StepRun>) -> Unit = {},
    ): ConnectOutcome {
        val runs = steps(mode, via, ownsServer).map { StepRun(it) }.toMutableList()
        fun set(i: Int, state: StepState, detail: String = "") {
            runs[i] = runs[i].copy(state = state, detail = detail); onUpdate(runs.toList())
        }
        onUpdate(runs.toList())
        for (i in runs.indices) {
            val id = runs[i].id
            val f = exec.facts()
            if (satisfied(id, f)) {
                set(i, StepState.SKIPPED, "already ok")
                log?.add("connect", "${id.label}: already ok")
                continue
            }
            set(i, StepState.RUNNING)
            val r = runCatching { exec.run(id) }.getOrElse { StepResult(false, it.message ?: it.javaClass.simpleName) }
            when {
                r.ok -> { set(i, StepState.DONE, r.detail); log?.add("connect", "${id.label}: ok ${r.detail}".trim()) }
                r.needsUser -> {
                    set(i, StepState.WAITING, r.detail)
                    log?.add("connect", "${id.label}: waiting for you ${r.detail}".trim())
                    return ConnectOutcome(runs.toList(), ConnectStatus.WAITING_FOR_USER, id, explain(id, r.detail, true))
                }
                else -> {
                    set(i, StepState.FAILED, r.detail)
                    log?.add("connect", "${id.label}: FAILED ${r.detail}".trim())
                    return ConnectOutcome(runs.toList(), ConnectStatus.FAILED, id, explain(id, r.detail, false))
                }
            }
        }
        return ConnectOutcome(runs.toList(), ConnectStatus.SUCCESS)
    }
}
